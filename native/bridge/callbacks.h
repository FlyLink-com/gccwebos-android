#ifndef GCC_CALLBACKS_H
#define GCC_CALLBACKS_H
#include <stdint.h>
typedef int (*gcc_protect_fn)(int fd, void *context);
void gcc_set_protector(gcc_protect_fn fn, void *context);
int gcc_protect_socket(int fd);
#endif
