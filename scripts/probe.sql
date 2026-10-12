DROP TABLE IF EXISTS scratch_b;
CREATE TABLE scratch_b (id UUID, priority VARCHAR(20), joined_at TIMESTAMPTZ, status VARCHAR(20));
INSERT INTO scratch_b VALUES
 (gen_random_uuid(), 'NORMAL', NOW() - INTERVAL '9 seconds', 'WAITING'),
 (gen_random_uuid(), 'NORMAL', NOW() - INTERVAL '5 seconds', 'WAITING'),
 (gen_random_uuid(), 'NORMAL', NOW(), 'WAITING');
PREPARE t_uuid(uuid) AS
SELECT COUNT(*) FROM scratch_b e
WHERE e.status = 'WAITING'
AND ((CASE WHEN e.priority = 'EMERGENCY' THEN 0 ELSE 1 END),
     (-((CASE e.priority WHEN 'PRIORITY' THEN 1 ELSE 0 END))),
     EXTRACT(EPOCH FROM e.joined_at), e.id)
  < (1, 0, 1791232500, $1);
PREPARE t_epoch(bigint) AS
SELECT COUNT(*) FROM scratch_b e
WHERE e.status = 'WAITING'
AND ((CASE WHEN e.priority = 'EMERGENCY' THEN 0 ELSE 1 END),
     (-((CASE e.priority WHEN 'PRIORITY' THEN 1 ELSE 0 END))),
     EXTRACT(EPOCH FROM e.joined_at), e.id)
  < (1, 0, $1, '00000000-0000-0000-0000-000000000000'::uuid);
SELECT id FROM scratch_b ORDER BY joined_at;
EXECUTE t_uuid('a5285fc4-aa9e-46eb-88b0-0d210bb0d950');
EXECUTE t_epoch(1999999999);
