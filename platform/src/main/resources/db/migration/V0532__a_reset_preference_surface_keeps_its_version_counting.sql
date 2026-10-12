-- ADR 0160: a reset keeps the surface's version counting.
--
-- A reset used to DELETE the row, so the next first write inserted it again at version 1. A device that
-- had read version 1 before the reset (a tablet nobody had touched for a week) still held a version the
-- table now accepted: its late write matched `version = 1` and replaced the new document with an edit
-- built on the old one. A version is only a fence while it never repeats for the same surface.
--
-- A reset now empties the row instead: the sealed document is replaced by a sealed `{}`, `reset_at` is
-- stamped and the version moves on by one, in one UPDATE ordered against any write to the row by that
-- version. A row with `reset_at` set is a marker and nothing else: it is not returned by a read, it does
-- not count against a person's twelve surfaces, a write that names a version for it is stale, and the
-- first write that names none brings it back at marker version + 1. What the person saved is gone
-- (the ciphertext is overwritten), and anonymisation still deletes every row of the person, markers
-- included.
--
-- The column is a timestamp, not text: the table still holds no column a name or a phone number can be
-- written into other than the sealed document, the subject and the surface.

ALTER TABLE iam.staff_preferences
    ADD COLUMN reset_at timestamptz;

COMMENT ON COLUMN iam.staff_preferences.reset_at IS
    'ADR 0160. Set when the person reset the surface: the row is then a version marker with an emptied document, absent to every read, and the next first write revives it at version + 1 so that a version is never reused.';
