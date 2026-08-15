# I2PMessenger — SAM stability fix

## Main bug found

`SamConnection` used a non-reentrant `kotlinx.coroutines.sync.Mutex`.

Several methods acquired `commandMutex` and then called another method that acquired the same mutex again:

- `lookupDestination()` -> `hello()`
- `generateDestination()` -> `hello()`
- `createStreamSession()` -> `hello()`

That is a deadlock. For example:

    lookupDestination()
      -> commandMutex.withLock
          -> hello()
              -> commandMutex.withLock  // waits forever

This explains why NAMING LOOKUP and SESSION CREATE could stop progressing. The outer timeout in `I2PManager` did not make the design correct.

## Fix

The control path now has:

- `connectInternal()` — connects without acquiring the mutex.
- `ensureHelloLocked()` — ensures HELLO while the caller already owns the mutex.
- public `connect()` / `hello()` acquire the mutex only once.
- `lookupDestination()`, `generateDestination()`, and `createStreamSession()` call `ensureHelloLocked()` instead of recursively calling `hello()`.

The SAM control socket read timeout is handled by the Java socket's `soTimeout`. A coroutine timeout around a blocking `InputStream.read()` was removed because cancellation does not reliably interrupt a blocking Java socket read.

## Message dispatch fix

`I2PManager.setOnMessageReceived()` now returns `Boolean`.

If the system handler (`PresenceManager`) returns `true`, the incoming packet is considered consumed and is not forwarded to normal chat listeners.

## App lifecycle cleanup

`GlobalScope` in `App` was replaced with an application-owned `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.

## Verification

The project was statically inspected after the changes. A local Gradle compile could not be run in this environment because the wrapper attempted to download Gradle 9.3.1 from services.gradle.org, and external network access is unavailable here.

Run in AndroidIDE:

    ./gradlew :app:compileDebugKotlin

Then test, in order:

1. startup / HELLO;
2. existing identity -> SESSION CREATE;
3. Base32 NAMING LOOKUP;
4. STREAM CONNECT;
5. send a normal message;
6. receive a normal message;
7. presence is consumed and does not appear in ChatActivity.
