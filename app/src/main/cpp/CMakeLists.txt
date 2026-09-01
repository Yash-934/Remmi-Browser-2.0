cmake_minimum_required(VERSION 3.4.1)

add_library(crash_forensics SHARED crash_forensics.cpp)

find_library(log-lib log)

target_link_libraries(crash_forensics ${log-lib})
