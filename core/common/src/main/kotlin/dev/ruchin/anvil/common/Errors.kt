package dev.ruchin.anvil.common

/**
 * Base class for every failure Anvil can explain to a human.
 * [userMessage] is shown in the UI; [message] (technical) goes to the debug log.
 */
open class AnvilException(
    val userMessage: String,
    technical: String = userMessage,
    cause: Throwable? = null,
) : Exception(technical, cause)

/** The USB drive vanished (unplugged, OTG cable wiggled, power brown-out). */
class DeviceDisconnectedException(technical: String, cause: Throwable? = null) : AnvilException(
    userMessage = "The USB drive was disconnected. Plug it back in and start again — " +
        "the drive will be fully rewritten, nothing on it is usable right now.",
    technical = technical,
    cause = cause,
)

class DeviceWriteProtectedException : AnvilException(
    "This USB drive is write-protected. Check for a lock switch on the drive or try another one."
)

class DeviceTooSmallException(val required: Long, val available: Long) : AnvilException(
    "This USB drive is too small: needs ${ByteSize.format(required)}, drive has ${ByteSize.format(available)}."
)

class VerificationFailedException(technical: String) : AnvilException(
    userMessage = "Verification failed: what was read back from the USB drive doesn't match the image. " +
        "The drive may be counterfeit or failing. Try a different USB drive.",
    technical = technical,
)
