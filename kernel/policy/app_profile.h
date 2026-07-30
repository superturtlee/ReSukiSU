#ifndef __KSU_H_APP_PROFILE
#define __KSU_H_APP_PROFILE

#include "uapi/app_profile.h"

#ifdef CONFIG_64BIT
#define TIF_KSU_DISABLE_ESCAPE_WITH_ROOT 63
// bit 62 is taken by TIF_PROC_NON_PRIVILEGE (kernel/feature/sucompat.h)
#define TIF_KSU_DISABLE_KSU 61
#else
#define TIF_KSU_DISABLE_ESCAPE_WITH_ROOT 31
// bit 30 is taken by TIF_PROC_NON_PRIVILEGE (kernel/feature/sucompat.h)
#define TIF_KSU_DISABLE_KSU 29
#endif

// Escalate current process to root with the appropriate profile
int escape_with_root_profile(void);

void disable_seccomp(void);
void escape_to_root_for_init(void);

#endif
