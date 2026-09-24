package a429.events;

/** Append-only event kinds. Nothing mutates past events; revisions fork or append. */
public enum EventType {
    IMPORT_MATERIAL,
    IMPORT_DICT,
    IMPORT_PARAMS,
    PARAM_REVISION,
    DICT_REVISION,
    SOURCE_MUTATION,
    FORK,
    ADJUDICATE,
    ADJUDICATION_REVOKE,
    INVALIDATION,
    ARCHIVE_RUN,
    ARCHIVE_MUTATION
}
