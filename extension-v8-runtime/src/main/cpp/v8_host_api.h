#ifndef ORION_V8_HOST_API_H
#define ORION_V8_HOST_API_H

#include <string>

namespace orion::v8runtime {

class V8HostApiDispatcher {
public:
    static void CallHostApi(const std::string& extensionId,
                            int generationId,
                            const std::string& apiName,
                            const std::string& requestId,
                            const std::string& payloadJson);
};

} // namespace orion::v8runtime

#endif // ORION_V8_HOST_API_H
