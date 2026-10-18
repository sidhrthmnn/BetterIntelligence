# llama.cpp v0.7.0 leaves compile_pending set when Vulkan shader compilation
# throws. A second graph using that pipeline then waits forever. Observed on
# HONOR/Adreno 830 in ggml_vk_load_shaders after a failed warm-up. Preserve the
# exception, wake every waiter and release partially created Vulkan handles.
# Changes are deliberately checked against the pinned upstream source.
function(bi_vulkan_replace file before after)
    file(READ "${file}" text)
    string(FIND "${text}" "${after}" patched)
    if(NOT patched EQUAL -1)
        return()
    endif()
    string(FIND "${text}" "${before}" position)
    if(position EQUAL -1)
        message(FATAL_ERROR "Review pinned Vulkan shader failure recovery: ${file}")
    endif()
    string(REPLACE "${before}" "${after}" text "${text}")
    file(WRITE "${file}" "${text}")
endfunction()

set(_vk_types "${llama_SOURCE_DIR}/ggml/src/ggml-vulkan/ggml-vulkan-types.h")
set(_vk_source "${llama_SOURCE_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
bi_vulkan_replace("${_vk_types}" "#include <functional>" "#include <functional>\n#include <exception> // BI: retained shader compilation failure")
bi_vulkan_replace("${_vk_types}" "    bool compile_pending {};"
    "    bool compile_pending {};\n    std::exception_ptr compile_failure; // BI: protected by compile_mutex")
bi_vulkan_replace("${_vk_source}" "            wait_pipeline = pipeline;"
    "            if (pipeline->compile_failure) std::rethrow_exception(pipeline->compile_failure); // BI\n            wait_pipeline = pipeline;")
bi_vulkan_replace("${_vk_source}" [=[        ggml_vk_create_pipeline_func(device, task.pipeline, task.spv_size, task.spv_data,
                                     task.entrypoint, task.parameter_count, task.wg_denoms,
                                     task.specialization_constants, task.disable_robustness,
                                     task.require_full_subgroups, task.required_subgroup_size);]=]
[=[        try {
            ggml_vk_create_pipeline_func(device, task.pipeline, task.spv_size, task.spv_data,
                                         task.entrypoint, task.parameter_count, task.wg_denoms,
                                         task.specialization_constants, task.disable_robustness,
                                         task.require_full_subgroups, task.required_subgroup_size);
        } catch (...) {
            // BI: a failed claim must never leave another graph waiting forever.
            {
                std::lock_guard<std::mutex> failure_lock(device->compile_mutex);
                task.pipeline->compile_failure = std::current_exception();
                device->device.destroyPipeline(task.pipeline->pipeline);
                device->device.destroyPipelineLayout(task.pipeline->layout);
                device->device.destroyShaderModule(task.pipeline->shader_module);
                task.pipeline->pipeline = nullptr;
                task.pipeline->layout = nullptr;
                task.pipeline->shader_module = nullptr;
                task.pipeline->compile_pending = false;
            }
            device->compile_cv.notify_all();
            throw;
        }]=])
bi_vulkan_replace("${_vk_source}" [=[            return wait_pipeline->compiled.load();
        });]=]
[=[            return wait_pipeline->compiled.load() || wait_pipeline->compile_failure; // BI
        });
        if (wait_pipeline->compile_failure) std::rethrow_exception(wait_pipeline->compile_failure);]=])
