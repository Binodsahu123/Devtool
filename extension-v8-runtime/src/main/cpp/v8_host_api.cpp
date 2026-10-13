#include "v8_host_api.h"
#include "v8_jni.h"

namespace orion::v8runtime {

void V8HostApiDispatcher::CallHostApi(const std::string& extensionId,
                                      int generationId,
                                      const std::string& apiName,
                                      const std::string& requestId,
                                      const std::string& payloadJson) {
    DispatchHostApiCall(extensionId, generationId, apiName, requestId, payloadJson);
}

} // namespace orion::v8runtime
