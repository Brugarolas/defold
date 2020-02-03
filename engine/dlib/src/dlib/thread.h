#ifndef DM_THREAD_H
#define DM_THREAD_H

#include <stdint.h>

#include <dlib/threadtypes.h>

namespace dmThread
{
    typedef void (*ThreadStart)(void*);

    /**
     * Create a new named thread
     * @note thread name currently not supported on win32
     * @param thread_start Thread entry function
     * @param stack_size Stack size
     * @param arg Thread argument
     * @param name Thread name
     * @return Thread handle
     */
    Thread New(ThreadStart thread_start, uint32_t stack_size, void* arg, const char* name);

    /**
     * Join thread
     * @param thread Thread to join
     */
    void Join(Thread thread);

    /**
     * Allocate thread local storage key
     * @return Key
     */

    TlsKey AllocTls();

    /**
     * Free thread local storage key
     * @param key Key
     */

    void FreeTls(TlsKey key);

    /**
     * Set thread specific data
     * @param key Key
     * @param value Value
     */
    void SetTlsValue(TlsKey key, void* value);

    /**
     * Get thread specific data
     * @param key Key
     */
    void* GetTlsValue(TlsKey key);

    /** Gets the current thread
     * @return the current thread
     */
    Thread GetCurrentThread();

    /** Sets the current thread name
     * @param thread the thread
     * @param name the thread name
     * @note The thread argument is unused on Darwin (uses current thread)
     */
    void SetThreadName(Thread thread, const char* name);
}

#endif // DM_THREAD_H
