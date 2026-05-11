package dev.nrbf4j

/**
 * Base exception for all Nrbf4j errors.
 */
open class NrbfException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Raised when a requested object cannot be found in the object graph.
 */
class ObjectNotFoundException(
    message: String,
) : NrbfException(message)

/**
 * Raised when a lookup expected exactly one object but found multiple matches.
 */
class AmbiguousObjectException(
    message: String,
) : NrbfException(message)

/**
 * Raised when a member cannot be found on an object.
 */
class MemberNotFoundException(
    message: String,
) : NrbfException(message)

/**
 * Raised when a member name lookup matches more than one member.
 */
class AmbiguousMemberException(
    message: String,
) : NrbfException(message)

/**
 * Raised when the binary stream is malformed or cannot be parsed.
 */
class NrbfFormatException(
    message: String,
    cause: Throwable? = null,
) : NrbfException(message, cause)
