-- Local development seed: one account to log in as.
--
-- The password is literally 'changeme', bcrypt-hashed at cost 10. This is a dev
-- convenience so `bootRun` + api.http work out of the box, NOT a credential — a real
-- deployment should create its own accounts and delete this row.
INSERT INTO users (email, password)
VALUES ('admin@pixerion.local', '$2a$10$/ISVtLw93meCt/UIsK6jBOkng7z7bSMKmiYEhH5rIQbdWZmqir/aq');

-- Link by lookup rather than hardcoded ids, so this survives the identity sequence
-- starting anywhere.
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u,
     roles r
WHERE u.email = 'admin@pixerion.local'
  AND r.name IN ('ROLE_USER', 'ROLE_ADMIN');
