#pragma once
#include <string>
#include <unordered_set>
#include <v8.h>

namespace orion::v8bridge {
std::string ValueToJson(v8::Isolate* isolate, v8::Local<v8::Value> value);
v8::Local<v8::Value> JsonToValue(v8::Isolate* isolate, v8::Local<v8::Context> context, const std::string& json);
}
