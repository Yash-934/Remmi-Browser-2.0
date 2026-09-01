#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>

#define LOG_TAG "NativeCrashForensics"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static struct sigaction old_sa[NSIG];

void crash_handler(int sig, siginfo_t *info, void *context) {
    char thread_name[16] = "unknown";
    pthread_getname_np(pthread_self(), thread_name, sizeof(thread_name));
    
    const char *sig_name = "UNKNOWN";
    switch(sig) {
        case SIGSEGV: sig_name = "SIGSEGV"; break;
        case SIGABRT: sig_name = "SIGABRT"; break;
        case SIGBUS:  sig_name = "SIGBUS"; break;
        case SIGILL:  sig_name = "SIGILL"; break;
    }
    
    LOGE("========================================");
    LOGE("FATAL NATIVE CRASH DETECTED");
    LOGE("EXACT SIGNAL: %s (Signal %d)", sig_name, sig);
    LOGE("CRASHING THREAD: %s (ID: %ld)", thread_name, (long)gettid());
    LOGE("See tombstone in logcat for full NATIVE STACK.");
    LOGE("========================================");

    // Call the original handler
    if (old_sa[sig].sa_flags & SA_SIGINFO) {
        if (old_sa[sig].sa_sigaction != NULL) {
            old_sa[sig].sa_sigaction(sig, info, context);
        }
    } else {
        if (old_sa[sig].sa_handler != SIG_DFL && old_sa[sig].sa_handler != SIG_IGN) {
            old_sa[sig].sa_handler(sig);
        }
    }
    
    // Default action to ensure process terminates and tombstone is generated
    signal(sig, SIG_DFL);
    raise(sig);
}

extern "C" JNIEXPORT void JNICALL
Java_com_remmi_browser_util_CrashHandlerHelper_installNativeCrashHandler(JNIEnv *env, jobject thiz) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sa.sa_sigaction = crash_handler;
    
    sigaction(SIGSEGV, &sa, &old_sa[SIGSEGV]);
    sigaction(SIGABRT, &sa, &old_sa[SIGABRT]);
    sigaction(SIGBUS, &sa, &old_sa[SIGBUS]);
    sigaction(SIGILL, &sa, &old_sa[SIGILL]);
    
    LOGE("Native crash forensics installed for SIGSEGV, SIGABRT, SIGBUS, SIGILL.");
}
