#ifndef ORION_V8_TASK_RUNNER_H
#define ORION_V8_TASK_RUNNER_H

#include <functional>
#include <queue>
#include <mutex>
#include <string>

namespace orion::v8runtime {

struct V8Task {
    std::string extensionId;
    int generationId = 0;
    std::function<void()> work;
};

class V8TaskScheduler {
public:
    static V8TaskScheduler& GetInstance();

    void PostTask(const std::string& extensionId, int generationId, std::function<void()> task);
    void ProcessPendingTasks(const std::string& extensionId);
    void ClearTasks(const std::string& extensionId);

private:
    std::mutex m_mutex;
    std::queue<V8Task> m_taskQueue;
};

} // namespace orion::v8runtime

#endif // ORION_V8_TASK_RUNNER_H
