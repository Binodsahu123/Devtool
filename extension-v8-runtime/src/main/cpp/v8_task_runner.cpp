#include "v8_task_runner.h"

namespace orion::v8runtime {

V8TaskScheduler& V8TaskScheduler::GetInstance() {
    static V8TaskScheduler instance;
    return instance;
}

void V8TaskScheduler::PostTask(const std::string& extensionId, int generationId, std::function<void()> task) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_taskQueue.push(V8Task{extensionId, generationId, task});
}

void V8TaskScheduler::ProcessPendingTasks(const std::string& extensionId) {
    std::lock_guard<std::mutex> lock(m_mutex);
    std::queue<V8Task> remaining;
    while (!m_taskQueue.empty()) {
        auto task = m_taskQueue.front();
        m_taskQueue.pop();
        if (task.extensionId == extensionId) {
            if (task.work) task.work();
        } else {
            remaining.push(task);
        }
    }
    m_taskQueue = remaining;
}

void V8TaskScheduler::ClearTasks(const std::string& extensionId) {
    std::lock_guard<std::mutex> lock(m_mutex);
    std::queue<V8Task> remaining;
    while (!m_taskQueue.empty()) {
        auto task = m_taskQueue.front();
        m_taskQueue.pop();
        if (task.extensionId != extensionId) {
            remaining.push(task);
        }
    }
    m_taskQueue = remaining;
}

} // namespace orion::v8runtime
