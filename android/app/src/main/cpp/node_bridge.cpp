#include <jni.h>
#include <node.h>
#include <unistd.h>
#include <string>
#include <vector>
#include <cstdlib>
#include <cerrno>
#include <cstddef>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <linux/seccomp.h>
#include <linux/filter.h>

static bool denyProcessExecution() {
    // Node may create worker threads, but generated backends cannot exec a shell/binary.
    sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execve, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execveat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
    };
    sock_fprog filter{static_cast<unsigned short>(sizeof(instructions) / sizeof(instructions[0])), instructions};
    return prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0 && prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &filter) == 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pocketsubstrate_node_NodeService_runNode(JNIEnv* env, jobject, jint input, jint output, jbyteArray source) {
    // Pipe descriptors are the only resources handed across the isolated UID boundary.
    if (dup2(input, STDIN_FILENO) < 0 || dup2(output, STDOUT_FILENO) < 0) return -1;
    close(input); close(output);
    chdir("/");
    unsetenv("NODE_OPTIONS");
    if (!denyProcessExecution()) return -2;
    // Java strings use modified UTF-8 at the JNI string boundary; pass real UTF-8
    // bytes so supplementary characters in generated source remain intact.
    std::string script(static_cast<size_t>(env->GetArrayLength(source)), '\0');
    env->GetByteArrayRegion(source, 0, static_cast<jsize>(script.size()), reinterpret_cast<jbyte*>(script.data()));
    if (env->ExceptionCheck()) return -3;
    std::vector<std::string> args = {"node", "--no-addons", "-e", script};
    size_t size = 0;
    for (auto& arg : args) size += arg.size() + 1;
    std::vector<char> buffer(size);
    std::vector<char*> argv;
    size_t offset = 0;
    for (auto& arg : args) {
        argv.push_back(buffer.data() + offset);
        std::copy(arg.begin(), arg.end(), buffer.begin() + offset);
        offset += arg.size() + 1;
    }
    return node::Start(static_cast<int>(argv.size()), argv.data());
}
