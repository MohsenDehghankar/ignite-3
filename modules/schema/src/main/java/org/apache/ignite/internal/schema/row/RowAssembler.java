/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.schema.row;

import static org.apache.ignite.internal.schema.BinaryRow.KEY_CHUNK_OFFSET;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.BitSet;
import java.util.UUID;
import org.apache.ignite.internal.schema.AssemblyException;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.schema.BitmaskNativeType;
import org.apache.ignite.internal.schema.ByteBufferRow;
import org.apache.ignite.internal.schema.Column;
import org.apache.ignite.internal.schema.Columns;
import org.apache.ignite.internal.schema.DecimalNativeType;
import org.apache.ignite.internal.schema.InvalidTypeException;
import org.apache.ignite.internal.schema.NativeType;
import org.apache.ignite.internal.schema.NativeTypeSpec;
import org.apache.ignite.internal.schema.NativeTypes;
import org.apache.ignite.internal.schema.NumberNativeType;
import org.apache.ignite.internal.schema.SchemaDescriptor;
import org.apache.ignite.internal.schema.SchemaMismatchException;
import org.apache.ignite.internal.schema.TemporalNativeType;
import org.apache.ignite.internal.util.HashUtils;

/**
 * Utility class to build rows using column appending pattern. The external user of this class must consult with the schema and provide the
 * columns in strict internal column sort order during the row construction.
 *
 * <p>Additionally, the user of this class should pre-calculate the resulting row size when possible to avoid unnecessary data copies and
 * allow some size optimizations to be applied.
 *
 * <p>Natively supported temporal types are encoded automatically with preserving sort order before writing.
 *
 * @see #utf8EncodedLength(CharSequence)
 * @see TemporalTypesHelper
 */
public class RowAssembler {
    /** Schema. */
    private final SchemaDescriptor schema;

    /** The number of non-null varlen columns in values chunk. */
    private final int valVartblLen;

    /** Target byte buffer to write to. */
    private final ExpandableByteBuf buf;

    /** Current columns chunk. */
    private Columns curCols;

    /** Current field index (the field is unset). */
    private int curCol;

    /** Current offset for the next column to be appended. */
    private int curOff;

    /** Index of the current varlen table entry. Incremented each time non-null varlen column is appended. */
    private int curVartblEntry;

    /** Base offset of the current chunk. */
    private int baseOff;

    /** Offset of the null-map for current chunk. */
    private int nullMapOff;

    /** Offset of the varlen table for current chunk. */
    private int varTblOff;

    /** Offset of data for current chunk. */
    private int dataOff;

    /** Flags. */
    private byte flags;

    /** Charset encoder for strings. Initialized lazily. */
    private CharsetEncoder strEncoder;

    private int keyChunkLength;

    /**
     * Get total size of the varlen table.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     *
     * @param entries Number of non-null varlen columns.
     * @return Total size of the varlen table.
     */
    private static int varTableChunkLength(int entries, int entrySize) {
        return entries <= 1 ? 0 : Short.BYTES + (entries - 1) * entrySize;
    }

    /**
     * Calculates encoded string length.
     *
     * @param seq Char sequence.
     * @return Encoded string length.
     * @implNote This implementation is not tolerant to malformed char sequences.
     */
    public static int utf8EncodedLength(CharSequence seq) {
        int cnt = 0;

        for (int i = 0, len = seq.length(); i < len; i++) {
            char ch = seq.charAt(i);

            if (ch <= 0x7F) {
                cnt++;
            } else if (ch <= 0x7FF) {
                cnt += 2;
            } else if (Character.isHighSurrogate(ch)) {
                cnt += 4;
                ++i;
            } else {
                cnt += 3;
            }
        }

        return cnt;
    }

    /**
     * Helper method.
     *
     * @param rowAsm Writes column value to assembler.
     * @param col    Column.
     * @param val    Value.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public static void writeValue(RowAssembler rowAsm, Column col, Object val) throws SchemaMismatchException {
        writeValue(rowAsm, col.type(), val);
    }

    /**
     * Helper method.
     *
     * @param rowAsm Writes a value as a specified type to assembler.
     * @param type   Type of the value.
     * @param val    Value.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public static void writeValue(RowAssembler rowAsm, NativeType type, Object val) throws SchemaMismatchException {
        if (val == null) {
            rowAsm.appendNull();

            return;
        }

        switch (type.spec()) {
            case INT8: {
                rowAsm.appendByte((byte) val);

                break;
            }
            case INT16: {
                rowAsm.appendShort((short) val);

                break;
            }
            case INT32: {
                rowAsm.appendInt((int) val);

                break;
            }
            case INT64: {
                rowAsm.appendLong((long) val);

                break;
            }
            case FLOAT: {
                rowAsm.appendFloat((float) val);

                break;
            }
            case DOUBLE: {
                rowAsm.appendDouble((double) val);

                break;
            }
            case UUID: {
                rowAsm.appendUuid((UUID) val);

                break;
            }
            case TIME: {
                rowAsm.appendTime((LocalTime) val);

                break;
            }
            case DATE: {
                rowAsm.appendDate((LocalDate) val);

                break;
            }
            case DATETIME: {
                rowAsm.appendDateTime((LocalDateTime) val);

                break;
            }
            case TIMESTAMP: {
                rowAsm.appendTimestamp((Instant) val);

                break;
            }
            case STRING: {
                rowAsm.appendString((String) val);

                break;
            }
            case BYTES: {
                rowAsm.appendBytes((byte[]) val);

                break;
            }
            case BITMASK: {
                rowAsm.appendBitmask((BitSet) val);

                break;
            }
            case NUMBER: {
                rowAsm.appendNumber((BigInteger) val);

                break;
            }
            case DECIMAL: {
                rowAsm.appendDecimal((BigDecimal) val);

                break;
            }
            default:
                throw new InvalidTypeException("Unexpected value: " + type);
        }
    }

    /**
     * Calculates byte size for BigInteger value.
     */
    public static int sizeInBytes(BigInteger val) {
        return val.bitLength() / 8 + 1;
    }

    /**
     * Calculates byte size for BigDecimal value.
     */
    public static int sizeInBytes(BigDecimal val) {
        return sizeInBytes(val.unscaledValue());
    }

    /**
     * Creates RowAssembler for chunks of unknown size.
     *
     * <p>RowAssembler will use adaptive buffer size and omit some optimizations for small key/value chunks.
     *
     * @param schema               Row schema.
     * @param nonNullVarlenKeyCols Number of non-null varlen columns in key chunk.
     * @param nonNullVarlenValCols Number of non-null varlen columns in value chunk.
     */
    public RowAssembler(
            SchemaDescriptor schema,
            int nonNullVarlenKeyCols,
            int nonNullVarlenValCols
    ) {
        this(schema,
                0,
                nonNullVarlenKeyCols,
                0,
                nonNullVarlenValCols);
    }

    /**
     * Creates RowAssembler for chunks with estimated sizes.
     *
     * <p>RowAssembler will apply optimizations based on chunks sizes estimations.
     *
     * @param schema        Row schema.
     * @param keyVarlenSize Key payload size. Estimated upper-bound or zero if unknown.
     * @param keyVarlenCols Number of non-null varlen columns in key chunk.
     * @param valVarlenSize Value data size. Estimated upper-bound or zero if unknown.
     * @param valVarlenCols Number of non-null varlen columns in value chunk.
     */
    public RowAssembler(
            SchemaDescriptor schema,
            int keyVarlenSize,
            int keyVarlenCols,
            int valVarlenSize,
            int valVarlenCols
    ) {
        this.schema = schema;

        curCols = schema.keyColumns();
        curCol = 0;
        strEncoder = null;

        int keyVartblLen = varTableChunkLength(keyVarlenCols, Integer.BYTES);
        valVartblLen = varTableChunkLength(valVarlenCols, Integer.BYTES);

        initChunk(KEY_CHUNK_OFFSET, curCols.nullMapSize(), keyVartblLen);

        final Columns valCols = schema.valueColumns();

        int size = BinaryRow.HEADER_SIZE + 2 * BinaryRow.CHUNK_LEN_FLD_SIZE
                + keyVarlenSize + valVarlenSize
                + keyVartblLen + valVartblLen
                + curCols.fixsizeMaxLen() + valCols.fixsizeMaxLen()
                + curCols.nullMapSize() + valCols.nullMapSize();

        buf = new ExpandableByteBuf(size);
        buf.putShort(0, (short) schema.version());
    }

    /**
     * Appends {@code null} value for the current column to the chunk.
     *
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If the current column is not nullable.
     */
    public RowAssembler appendNull() throws SchemaMismatchException {
        if (!curCols.column(curCol).nullable()) {
            throw new SchemaMismatchException(
                    "Failed to set column (null was passed, but column is not nullable): " + curCols.column(curCol));
        }

        setNull(curCol);

        shiftColumn(0);

        return this;
    }

    /**
     * Appends byte value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendByte(byte val) throws SchemaMismatchException {
        checkType(NativeTypes.INT8);

        buf.put(curOff, val);

        shiftColumn(NativeTypes.INT8.sizeInBytes());

        return this;
    }

    /**
     * Appends short value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendShort(short val) throws SchemaMismatchException {
        checkType(NativeTypes.INT16);

        buf.putShort(curOff, val);

        shiftColumn(NativeTypes.INT16.sizeInBytes());

        return this;
    }

    /**
     * Appends int value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendInt(int val) throws SchemaMismatchException {
        checkType(NativeTypes.INT32);

        buf.putInt(curOff, val);

        shiftColumn(NativeTypes.INT32.sizeInBytes());

        return this;
    }

    /**
     * Appends long value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendLong(long val) throws SchemaMismatchException {
        checkType(NativeTypes.INT64);

        buf.putLong(curOff, val);

        shiftColumn(NativeTypes.INT64.sizeInBytes());

        return this;
    }

    /**
     * Appends float value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendFloat(float val) throws SchemaMismatchException {
        checkType(NativeTypes.FLOAT);

        buf.putFloat(curOff, val);

        shiftColumn(NativeTypes.FLOAT.sizeInBytes());

        return this;
    }

    /**
     * Appends double value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendDouble(double val) throws SchemaMismatchException {
        checkType(NativeTypes.DOUBLE);

        buf.putDouble(curOff, val);

        shiftColumn(NativeTypes.DOUBLE.sizeInBytes());

        return this;
    }

    /**
     * Appends BigInteger value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendNumber(BigInteger val) throws SchemaMismatchException {
        checkType(NativeTypeSpec.NUMBER);

        Column col = curCols.column(curCol);

        NumberNativeType type = (NumberNativeType) col.type();

        //0 is a magic number for "unlimited precision"
        if (type.precision() > 0 && new BigDecimal(val).precision() > type.precision()) {
            throw new SchemaMismatchException("Failed to set number value for column '" + col.name() + "' "
                    + "(max precision exceeds allocated precision) "
                    + "[number=" + val + ", max precision=" + type.precision() + "]");
        }

        byte[] bytes = val.toByteArray();

        buf.putBytes(curOff, bytes);

        writeVarlenOffset(curVartblEntry, curOff - dataOff);

        curVartblEntry++;

        shiftColumn(bytes.length);

        return this;
    }

    /**
     * Appends BigDecimal value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendDecimal(BigDecimal val) throws SchemaMismatchException {
        checkType(NativeTypeSpec.DECIMAL);

        Column col = curCols.column(curCol);

        DecimalNativeType type = (DecimalNativeType) col.type();

        val = val.setScale(type.scale(), RoundingMode.HALF_UP);

        if (val.precision() > type.precision()) {
            throw new SchemaMismatchException("Failed to set decimal value for column '" + col.name() + "' "
                    + "(max precision exceeds allocated precision)"
                    + " [decimal=" + val + ", max precision=" + type.precision() + "]");
        }

        byte[] bytes = val.unscaledValue().toByteArray();

        buf.putBytes(curOff, bytes);

        writeVarlenOffset(curVartblEntry, curOff - dataOff);

        curVartblEntry++;

        shiftColumn(bytes.length);

        return this;
    }

    /**
     * Appends UUID value for the current column to the chunk.
     *
     * @param uuid Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendUuid(UUID uuid) throws SchemaMismatchException {
        checkType(NativeTypes.UUID);

        buf.putLong(curOff, uuid.getLeastSignificantBits());
        buf.putLong(curOff + 8, uuid.getMostSignificantBits());

        shiftColumn(NativeTypes.UUID.sizeInBytes());

        return this;
    }

    /**
     * Appends String value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendString(String val) throws SchemaMismatchException {
        checkType(NativeTypeSpec.STRING);

        try {
            final int written = buf.putString(curOff, val, encoder());

            writeVarlenOffset(curVartblEntry, curOff - dataOff);

            curVartblEntry++;

            shiftColumn(written);

            return this;
        } catch (CharacterCodingException e) {
            throw new AssemblyException("Failed to encode string", e);
        }
    }

    /**
     * Appends byte[] value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendBytes(byte[] val) throws SchemaMismatchException {
        checkType(NativeTypeSpec.BYTES);

        buf.putBytes(curOff, val);

        writeVarlenOffset(curVartblEntry, curOff - dataOff);

        curVartblEntry++;

        shiftColumn(val.length);

        return this;
    }

    /**
     * Appends BitSet value for the current column to the chunk.
     *
     * @param bitSet Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendBitmask(BitSet bitSet) throws SchemaMismatchException {
        Column col = curCols.column(curCol);

        checkType(NativeTypeSpec.BITMASK);

        BitmaskNativeType maskType = (BitmaskNativeType) col.type();

        if (bitSet.length() > maskType.bits()) {
            throw new IllegalArgumentException("Failed to set bitmask for column '" + col.name() + "' "
                    + "(mask size exceeds allocated size) [mask=" + bitSet + ", maxSize=" + maskType.bits() + "]");
        }

        byte[] arr = bitSet.toByteArray();

        buf.putBytes(curOff, arr);

        for (int i = 0; i < maskType.sizeInBytes() - arr.length; i++) {
            buf.put(curOff + arr.length + i, (byte) 0);
        }

        shiftColumn(maskType.sizeInBytes());

        return this;
    }

    /**
     * Appends LocalDate value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendDate(LocalDate val) throws SchemaMismatchException {
        checkType(NativeTypes.DATE);

        int date = TemporalTypesHelper.encodeDate(val);

        writeDate(curOff, date);

        shiftColumn(NativeTypes.DATE.sizeInBytes());

        return this;
    }

    /**
     * Appends LocalTime value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendTime(LocalTime val) throws SchemaMismatchException {
        checkType(NativeTypeSpec.TIME);

        TemporalNativeType type = (TemporalNativeType) curCols.column(curCol).type();

        writeTime(buf, curOff, val, type);

        shiftColumn(type.sizeInBytes());

        return this;
    }

    /**
     * Appends LocalDateTime value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendDateTime(LocalDateTime val) throws SchemaMismatchException {
        checkType(NativeTypeSpec.DATETIME);

        TemporalNativeType type = (TemporalNativeType) curCols.column(curCol).type();

        int date = TemporalTypesHelper.encodeDate(val.toLocalDate());

        writeDate(curOff, date);
        writeTime(buf, curOff + 3, val.toLocalTime(), type);

        shiftColumn(type.sizeInBytes());

        return this;
    }

    /**
     * Appends Instant value for the current column to the chunk.
     *
     * @param val Column value.
     * @return {@code this} for chaining.
     * @throws SchemaMismatchException If a value doesn't match the current column type.
     */
    public RowAssembler appendTimestamp(Instant val) throws SchemaMismatchException {
        checkType(NativeTypeSpec.TIMESTAMP);

        TemporalNativeType type = (TemporalNativeType) curCols.column(curCol).type();

        long seconds = val.getEpochSecond();
        int nanos = TemporalTypesHelper.normalizeNanos(val.getNano(), type.precision());

        buf.putLong(curOff, seconds);

        if (type.precision() != 0) {
            // Write only meaningful bytes.
            buf.putInt(curOff + 8, nanos);
        }

        shiftColumn(type.sizeInBytes());

        return this;
    }

    /**
     * Build serialized row.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     */
    public BinaryRow build() {
        flush();

        return new ByteBufferRow(buf.unwrap());
    }

    /**
     * Get row bytes.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     */
    public byte[] toBytes() {
        flush();

        return buf.toArray();
    }

    /**
     * Finish building row.
     */
    private void flush() {
        if (schema.keyColumns() == curCols) {
            throw new AssemblyException("Key column missed: colIdx=" + curCol);
        } else {
            if (curCol == 0) {
                // Row has no value
                buf.putShort(BinaryRow.SCHEMA_VERSION_OFFSET, (short) 0);
            } else if (schema.valueColumns().length() != curCol) {
                throw new AssemblyException("Value column missed: colIdx=" + curCol);
            }
        }

        int hash = HashUtils.hash32(buf.unwrap().array(), KEY_CHUNK_OFFSET, keyChunkLength, 0);

        buf.putInt(BinaryRow.KEY_HASH_FIELD_OFFSET, hash);
    }

    /**
     * Get UTF-8 string encoder.
     */
    private CharsetEncoder encoder() {
        if (strEncoder == null) {
            strEncoder = StandardCharsets.UTF_8.newEncoder();
        }

        return strEncoder;
    }

    /**
     * Writes the given offset to the varlen table entry with the given index.
     *
     * @param entryIdx Vartable entry index.
     * @param off      Offset to write.
     */
    private void writeVarlenOffset(int entryIdx, int off) {
        if (entryIdx == 0) {
            return; // Omit offset for very first varlen.
        }

        buf.putInt(varTblOff + Short.BYTES + (entryIdx - 1) * Integer.BYTES, off);
    }

    /**
     * Writes date.
     *
     * @param off  Offset.
     * @param date Compacted date.
     */
    private void writeDate(int off, int date) {
        buf.putShort(off, (short) (date >>> 8));
        buf.put(off + 2, (byte) (date & 0xFF));
    }

    /**
     * Writes time.
     *
     * @param buf  Buffer.
     * @param off  Offset.
     * @param val  Time.
     * @param type Native type.
     */
    static void writeTime(ExpandableByteBuf buf, int off, LocalTime val, TemporalNativeType type) {
        long time = TemporalTypesHelper.encodeTime(type, val);

        if (type.precision() > 3) {
            time = ((time >>> 32) << TemporalTypesHelper.NANOSECOND_PART_LEN) | (time & TemporalTypesHelper.NANOSECOND_PART_MASK);

            buf.putInt(off, (int) (time >>> 16));
            buf.putShort(off + 4, (short) (time & 0xFFFF_FFFFL));
        } else {
            time = ((time >>> 32) << TemporalTypesHelper.MILLISECOND_PART_LEN) | (time & TemporalTypesHelper.MILLISECOND_PART_MASK);

            buf.putInt(off, (int) time);
        }
    }

    /**
     * Checks that the type being appended matches the column type.
     *
     * @param type Type spec that is attempted to be appended.
     * @throws SchemaMismatchException If given type doesn't match the current column type.
     */
    private void checkType(NativeTypeSpec type) {
        Column col = curCols.column(curCol);

        if (col.type().spec() != type) {
            throw new SchemaMismatchException("Failed to set column (" + type.name() + " was passed, but column is of different "
                    + "type): " + col);
        }
    }

    /**
     * Checks that the type being appended matches the column type.
     *
     * @param type Type that is attempted to be appended.
     */
    private void checkType(NativeType type) {
        checkType(type.spec());
    }

    /**
     * Sets null flag in the null-map for the given column.
     *
     * @param colIdx Column index.
     */
    private void setNull(int colIdx) {
        assert nullMapOff < varTblOff : "Null-map is omitted.";

        int byteInMap = colIdx >> 3; // Equivalent expression for: colIidx / 8
        int bitInByte = colIdx & 7; // Equivalent expression for: colIdx % 8

        buf.ensureCapacity(nullMapOff + byteInMap + 1);

        buf.put(nullMapOff + byteInMap, (byte) ((Byte.toUnsignedInt(buf.get(nullMapOff + byteInMap))) | (1 << bitInByte)));
    }

    /**
     * Shifts current column indexes as necessary, also switch to value chunk writer when moving from key to value columns.
     */
    private void shiftColumn(int size) {
        curCol++;
        curOff += size;

        if (curCol == curCols.length()) {
            finishChunk();
        }
    }

    /**
     * Write chunk meta.
     */
    private void finishChunk() {
        if (curVartblEntry > 1) {
            assert varTblOff < dataOff : "Illegal writing of varlen when 'omit vartable' flag is set for a chunk.";
            assert varTblOff + varTableChunkLength(curVartblEntry, Integer.BYTES) == dataOff : "Vartable overlow: size=" + curVartblEntry;

            final VarTableFormat format = VarTableFormat.format(curOff - dataOff, valVartblLen);

            curOff -= format.compactVarTable(buf, varTblOff, curVartblEntry - 1);

            flags |= format.formatId();
        }

        // Write sizes.
        final int chunkLen = curOff - baseOff;

        buf.putInt(baseOff, chunkLen);
        buf.put(baseOff + BinaryRow.FLAGS_FIELD_OFFSET, flags);

        if (schema.keyColumns() == curCols) {
            keyChunkLength = chunkLen;

            switchToValueChunk(BinaryRow.HEADER_SIZE + chunkLen);
        }
    }

    /**
     * SwitchToValueChunk.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     *
     * @param baseOff Chunk base offset.
     */
    private void switchToValueChunk(int baseOff) {
        // Switch key->value columns.
        curCols = schema.valueColumns();
        curCol = 0;

        // Create value chunk writer.
        initChunk(baseOff, curCols.nullMapSize(), valVartblLen);
    }

    /**
     * Init chunk offsets and flags.
     *
     * @param baseOff    Chunk base offset.
     * @param nullMapLen Null-map length in bytes.
     * @param vartblLen  Vartable length in bytes.
     */
    private void initChunk(int baseOff, int nullMapLen, int vartblLen) {
        this.baseOff = baseOff;

        nullMapOff = baseOff + BinaryRow.CHUNK_LEN_FLD_SIZE + BinaryRow.FLAGS_FLD_SIZE;
        varTblOff = nullMapOff + nullMapLen;
        dataOff = varTblOff + vartblLen;
        curOff = dataOff;
        curVartblEntry = 0;
        flags = 0;
    }

    /**
     * IsKeyChunk.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     *
     * @return {@code true} if current chunk is a key chunk, {@code false} otherwise.
     */
    private boolean isKeyChunk() {
        return baseOff == KEY_CHUNK_OFFSET;
    }
}
