// SPDX-License-Identifier: GPL-3.0-or-later
#include "callbacks.h"
#include <pthread.h>
static pthread_mutex_t callback_lock = PTHREAD_MUTEX_INITIALIZER;
static gcc_protect_fn protect_callback = 0;
static void *protect_context = 0;
void gcc_set_protector(gcc_protect_fn fn, void *context) {
    pthread_mutex_lock(&callback_lock);
    protect_callback = fn;
    protect_context = context;
    pthread_mutex_unlock(&callback_lock);
}
int gcc_protect_socket(int fd) {
    pthread_mutex_lock(&callback_lock);
    int ok = protect_callback ? protect_callback(fd, protect_context) : 0;
    pthread_mutex_unlock(&callback_lock);
    return ok == 1;
}
