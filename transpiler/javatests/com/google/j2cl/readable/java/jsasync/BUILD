load(
    "//transpiler/javatests/com/google/j2cl/readable:readable_example.bzl",
    "readable_example",
)

package(licenses = ["notice"])

readable_example(
    srcs = glob(["*.java"]),
    # @JsAsync not supported in Kotlin.
    generate_kt_readables = False,
    generate_wasm_readables = False,  # JS varargs is not supported in WASM.
    deps = ["//third_party:gwt-jsinterop-annotations-j2cl"],
)
