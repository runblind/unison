#----------------------------------------------------------------
# Generated CMake target import file for configuration "Debug".
#----------------------------------------------------------------

# Commands may need to know the format version.
set(CMAKE_IMPORT_FILE_VERSION 1)

# Import target "kissfft::kissfft-float" for configuration "Debug"
set_property(TARGET kissfft::kissfft-float APPEND PROPERTY IMPORTED_CONFIGURATIONS DEBUG)
set_target_properties(kissfft::kissfft-float PROPERTIES
  IMPORTED_LINK_INTERFACE_LANGUAGES_DEBUG "C"
  IMPORTED_LOCATION_DEBUG "${_IMPORT_PREFIX}/lib/libkissfft-float.a"
  )

list(APPEND _IMPORT_CHECK_TARGETS kissfft::kissfft-float )
list(APPEND _IMPORT_CHECK_FILES_FOR_kissfft::kissfft-float "${_IMPORT_PREFIX}/lib/libkissfft-float.a" )

# Commands beyond this point should not need to know the version.
set(CMAKE_IMPORT_FILE_VERSION)
