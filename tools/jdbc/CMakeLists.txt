find_package(Java 1.8)
find_package(JNI)

cmake_minimum_required(VERSION 3.11.0)

if(NOT JNI_FOUND OR NOT Java_FOUND)
  message(FATAL_ERROR No compatible Java/JNI found)
endif()

include(UseJava)
project(DuckDBJDummy NONE)

file(GLOB JAVA_SRC_FILES src/main/java/org/duckdb/*.java)

set(CMAKE_JAVA_COMPILE_FLAGS -source 1.7 -target 1.7 -encoding utf-8)

add_jar(duckdb_jdbc ${JAVA_SRC_FILES}
        META-INF/services/java.sql.Driver
        GENERATE_NATIVE_HEADERS duckdb-native)

include_directories(../../extension/parquet/include)

add_library(duckdb_java SHARED src/jni/duckdb_java.cpp)
target_compile_options(duckdb_java PRIVATE -fexceptions)
target_link_libraries(duckdb_java duckdb-native duckdb_static parquet_extension)
if(OS_NAME STREQUAL "linux")
  target_link_libraries(duckdb_java -static-libgcc -static-libstdc++
  )# static link to libstdc++ to target more linux distro
endif()
if(APPLE)
  set(OS_ARCH universal)
endif()
string(JOIN "_" LIB_SUFFIX ".so" ${OS_NAME} ${OS_ARCH})
set_target_properties(duckdb_java PROPERTIES SUFFIX ${LIB_SUFFIX})
set_target_properties(duckdb_java PROPERTIES PREFIX "lib")

add_custom_command(
  OUTPUT dummy_jdbc_target
  DEPENDS duckdb_java duckdb_jdbc
  COMMAND ${Java_JAR_EXECUTABLE} uf duckdb_jdbc.jar -C
          $<TARGET_FILE_DIR:duckdb_java> $<TARGET_FILE_NAME:duckdb_java>)

set(JAR_DIR "${CMAKE_CURRENT_BINARY_DIR}/java_dependencies")

function(DLFILE HTTP_URL FILE_NAME)
  if(NOT EXISTS "${JAR_DIR}/${FILE_NAME}")
    file(DOWNLOAD ${HTTP_URL}
         ${JAR_DIR}/${FILE_NAME})
  endif()
endfunction()

set(ARROW_VERSION 9.0.0.dev385)
# download compile dependencies
DLFILE(https://nightlies.apache.org/arrow/java/org/apache/arrow/arrow-c-data/${ARROW_VERSION}/arrow-c-data-${ARROW_VERSION}.jar arrow-c-data.jar)
DLFILE(https://nightlies.apache.org/arrow/java/org/apache/arrow/arrow-memory-core/${ARROW_VERSION}/arrow-memory-core-${ARROW_VERSION}.jar arrow-memory-core.jar)
DLFILE(https://nightlies.apache.org/arrow/java/org/apache/arrow/arrow-vector/${ARROW_VERSION}/arrow-vector-${ARROW_VERSION}.jar arrow-vector.jar)
DLFILE(https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.13.3/jackson-annotations-2.13.3.jar jackson-annotations.jar)

set(TEST_COMPILE_DEPS
    ${JAR_DIR}/arrow-c-data.jar
    ${JAR_DIR}/arrow-memory-core.jar
    ${JAR_DIR}/arrow-vector.jar
    ${JAR_DIR}/jackson-annotations.jar)

FILE(GLOB JAVA_TEST_FILES src/test/java/org/duckdb/test/*.java)

add_jar(duckdb_jdbc_test ${JAVA_TEST_FILES}
        INCLUDE_JARS
        $<TARGET_FILE_DIR:duckdb_java>/duckdb_jdbc.jar
        ${TEST_COMPILE_DEPS})

DLFILE(https://nightlies.apache.org/arrow/java/org/apache/arrow/arrow-memory-netty/${ARROW_VERSION}/arrow-memory-netty-${ARROW_VERSION}.jar arrow-memory-netty.jar)
DLFILE(https://nightlies.apache.org/arrow/java/org/apache/arrow/arrow-format/${ARROW_VERSION}/arrow-format-${ARROW_VERSION}.jar arrow-format.jar)
DLFILE(https://repo1.maven.org/maven2/io/netty/netty-common/4.1.79.Final/netty-common-4.1.79.Final.jar netty-common.jar)
DLFILE(https://repo1.maven.org/maven2/io/netty/netty-buffer/4.1.79.Final/netty-buffer-4.1.79.Final.jar netty-buffer.jar)
DLFILE(https://repo1.maven.org/maven2/com/google/flatbuffers/flatbuffers-java/1.12.0/flatbuffers-java-1.12.0.jar flatbuffers-java.jar)
DLFILE(https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.13.3/jackson-core-2.13.3.jar jackson-core.jar)
DLFILE(https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.13.3/jackson-databind-2.13.3.jar jackson-databind.jar)
DLFILE(https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar slf4j-nop.jar)
DLFILE(https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar slf4j-api.jar)

set(TEST_CLASSPATH_DEPS
    ${CMAKE_CURRENT_BINARY_DIR}/duckdb_jdbc.jar
    ${JAR_DIR}/arrow-memory-netty.jar
    ${JAR_DIR}/arrow-format.jar
    ${JAR_DIR}/jackson-core.jar
    ${JAR_DIR}/jackson-databind.jar
    ${JAR_DIR}/slf4j-api.jar
    ${JAR_DIR}/slf4j-nop.jar
    ${JAR_DIR}/netty-common.jar
    ${JAR_DIR}/netty-buffer.jar
    ${JAR_DIR}/flatbuffers-java.jar)

list(APPEND ALL_CLASSPATH_DEPS ${TEST_COMPILE_DEPS} ${TEST_CLASSPATH_DEPS})
string(JOIN : TEST_CLASSPATH ${ALL_CLASSPATH_DEPS})

# write bash script to run jdbc tests (and pull in classpath from downloaded dependencies here)
write_file(${CMAKE_CURRENT_BINARY_DIR}/test_jdbc.sh "#!/bin/bash\njava -cp ${TEST_CLASSPATH}:${CMAKE_CURRENT_BINARY_DIR}/duckdb_jdbc_test.jar org.duckdb.test.TestDuckDBJDBC $@\nexit $?")

file(COPY ${CMAKE_CURRENT_BINARY_DIR}/test_jdbc.sh
    DESTINATION ${CMAKE_BINARY_DIR}
    FILE_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)

add_custom_target(jdbc ALL DEPENDS dummy_jdbc_target)
