#pragma once
#include "orion_v8_build_contract.h"
#ifndef ORION_HAS_V8
#define ORION_HAS_V8 0
#endif
#if ORION_HAS_V8
#define ORION_NATIVE_V8_BUILD_ENABLED 1
#else
#define ORION_NATIVE_V8_BUILD_ENABLED 0
#endif
