use jni::{
    JNIEnv,
    objects::{JByteArray, JObject},
    sys::jbyteArray,
};

/// The only exported JNI entry point. Byte arrays preserve ordinary UTF-8 and
/// are bounded before copying. No Java references survive the call.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_rustcore_NativeIntelligence_dispatch(
    env: JNIEnv,
    _receiver: JObject,
    input: JByteArray,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let length = env.get_array_length(&input).ok()?;
        if length < 0 {
            return None;
        }
        if length as usize > crate::MAX_REQUEST_BYTES {
            return env
                .byte_array_from_slice(br#"{"version":1,"ok":false,"error":"LIMIT_EXCEEDED"}"#)
                .ok();
        }
        let bytes = env.convert_byte_array(&input).ok()?;
        env.byte_array_from_slice(&crate::dispatch(&bytes)).ok()
    }));
    result
        .ok()
        .flatten()
        .map_or(std::ptr::null_mut(), |a| a.into_raw())
}
