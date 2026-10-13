#include "v8_value_converter.h"
#include <sstream>

namespace orion::v8bridge {
namespace {
std::string JsonString(v8::Isolate* isolate, v8::Local<v8::Value> value) {
    v8::String::Utf8Value utf8(isolate, value);
    return *utf8 ? *utf8 : "";
}

v8::Local<v8::Value> Normalize(v8::Isolate* isolate, v8::Local<v8::Context> context,
                               v8::Local<v8::Value> value, std::unordered_set<void*>& seen) {
    if (value->IsUndefined() || value->IsNull()) return v8::Null(isolate);
    if (value->IsString() || value->IsBoolean() || value->IsNumber()) return value;
    if (value->IsBigInt()) return v8::String::NewFromUtf8(isolate, JsonString(isolate, value).c_str()).ToLocalChecked();
    if (value->IsDate()) return value;
    if (value->IsRegExp()) return value;
    if (value->IsFunction()) return v8::Null(isolate);

    auto object = value.As<v8::Object>();
    void* identity = static_cast<void*>(*object);
    if (!seen.insert(identity).second) {
        return v8::String::NewFromUtf8Literal(isolate, "[Circular]");
    }
    if (value->IsArray()) {
        auto array = value.As<v8::Array>();
        const uint32_t len = array->Length();
        auto out = v8::Array::New(isolate, static_cast<int>(len));
        for (uint32_t i = 0; i < len; ++i) {
            v8::Local<v8::Value> item;
            if (array->Get(context, i).ToLocal(&item)) out->Set(context, i, Normalize(isolate, context, item, seen)).Check();
        }
        return out;
    }
    auto out = v8::Object::New(isolate);
    v8::Local<v8::Array> names;
    if (object->GetOwnPropertyNames(context).ToLocal(&names)) {
        for (uint32_t i = 0; i < names->Length(); ++i) {
            v8::Local<v8::Value> key;
            if (!names->Get(context, i).ToLocal(&key)) continue;
            v8::Local<v8::Value> item;
            if (!object->Get(context, key).ToLocal(&item)) continue;
            out->Set(context, key, Normalize(isolate, context, item, seen)).Check();
        }
    }
    return out;
}
}

std::string ValueToJson(v8::Isolate* isolate, v8::Local<v8::Value> value) {
    v8::HandleScope handle_scope(isolate);
    auto context = isolate->GetCurrentContext();
    std::unordered_set<void*> seen;
    auto normalized = Normalize(isolate, context, value, seen);
    auto json = v8::JSON::Stringify(context, normalized);
    if (json.IsEmpty()) return "null";
    return JsonString(isolate, json.ToLocalChecked());
}

v8::Local<v8::Value> JsonToValue(v8::Isolate* isolate, v8::Local<v8::Context> context, const std::string& json) {
    v8::EscapableHandleScope scope(isolate);
    auto source = v8::String::NewFromUtf8(isolate, json.c_str()).ToLocalChecked();
    auto value = v8::JSON::Parse(context, source);
    if (value.IsEmpty()) return scope.Escape(v8::Null(isolate));
    return scope.Escape(value.ToLocalChecked());
}
}
