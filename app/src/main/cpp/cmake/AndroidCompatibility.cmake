# llama.cpp v0.7.0's Vulkan host buffer helper used aligned_alloc (Android 28).
# posix_memalign has the same paired free() lifecycle and supports our API 24
# baseline. Keep this small compatibility patch checked against the pinned source.
set(_buffers "${llama_SOURCE_DIR}/ggml/src/ggml-vulkan/ggml-vulkan-buffers.cpp")
file(READ "${_buffers}" _text)
set(_original "return std::aligned_alloc(align, size);")
set(_portable "void * ptr = nullptr;\n    return posix_memalign(&ptr, align, size) == 0 ? ptr : nullptr; // BI: API 24")
string(FIND "${_text}" "${_original}" _position)
if(NOT _position EQUAL -1)
    string(REPLACE "${_original}" "${_portable}" _text "${_text}")
    file(WRITE "${_buffers}" "${_text}")
else()
    string(FIND "${_text}" "${_portable}" _position)
    if(_position EQUAL -1)
        # v0.8.0 implements the same portable allocation/free pairing upstream.
        string(FIND "${_text}" "static void * portable_aligned_malloc" _helper)
        string(FIND "${_text}" "posix_memalign(&ptr, align, size)" _posix)
        string(FIND "${_text}" "portable_aligned_free(entry.base)" _free)
        if(_helper EQUAL -1 OR _posix EQUAL -1 OR _free EQUAL -1)
            message(FATAL_ERROR "Review pinned Vulkan aligned allocation compatibility")
        endif()
    endif()
endif()
