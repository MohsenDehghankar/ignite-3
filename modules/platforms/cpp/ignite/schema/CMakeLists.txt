#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

project(ignite-schema)

set(TARGET ${PROJECT_NAME})

add_library(${TARGET} STATIC
    big_decimal.cpp big_decimal.h
    big_integer.cpp big_integer.h
    binary_tuple_builder.cpp binary_tuple_builder.h
    binary_tuple_header.h
    binary_tuple_parser.cpp binary_tuple_parser.h
    binary_tuple_schema.h
    column_info.h
    ignite_date.h
    ignite_date_time.h
    ignite_time.h
    ignite_timestamp.h
    ignite_type.cpp ignite_type.h
    types.h)

target_link_libraries(${TARGET} ignite-common)

ignite_test(bignum_test bignum_test.cpp LIBS ${TARGET})
