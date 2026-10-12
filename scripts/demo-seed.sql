-- Demo seed for Phase visual review ONLY (Harbor Clinic tenant).
-- Real shapes, relative timestamps, isolated organization. Re-runnable:
-- deletes the demo org first (cascades are absent by design, so delete
-- in dependency order). Never applied via Flyway; never committed as
-- a migration. Run from backend/: psql -h localhost -U smartqueue -d smartqueue -f scripts/demo-seed.sql
-- Password for all demo users: Demo-pass-9 (BCrypt, encoder-generated).

\set DEMO_HASH '$2a$10$VP7.Re8OmsC1k2YZDF/z9udwuPemj00.Ba.C49wGMNp835XFnboua'

-- Org: 11111111-1111-1111-1111-111111111111
DELETE FROM service_session WHERE branch_id IN (SELECT id FROM branch WHERE organization_id = '11111111-1111-1111-1111-111111111111');
DELETE FROM notification WHERE organization_id = '11111111-1111-1111-1111-111111111111';
DELETE FROM queue_entry WHERE organization_id = '11111111-1111-1111-1111-111111111111';
DELETE FROM queue_token_counter WHERE queue_id IN (SELECT id FROM queue WHERE organization_id = '11111111-1111-1111-1111-111111111111');
DELETE FROM queue WHERE organization_id = '11111111-1111-1111-1111-111111111111';
DELETE FROM appointment WHERE organization_id = '11111111-1111-1111-1111-111111111111';
DELETE FROM counter_capability WHERE branch_id IN (SELECT id FROM branch WHERE organization_id = '11111111-1111-1111-1111-111111111111');
DELETE FROM staff_assignment WHERE organization_id = '11111111-1111-1111-1111-111111111111';
DELETE FROM organization_membership WHERE organization_id = '11111111-1111-1111-1111-111111111111';
DELETE FROM user_credential WHERE user_id IN (SELECT id FROM app_user WHERE organization_id = '11111111-1111-1111-1111-111111111111');
DELETE FROM user_role WHERE user_id IN (SELECT id FROM app_user WHERE organization_id = '11111111-1111-1111-1111-111111111111');
DELETE FROM app_user WHERE organization_id = '11111111-1111-1111-1111-111111111111';
DELETE FROM service WHERE branch_id IN (SELECT id FROM branch WHERE organization_id = '11111111-1111-1111-1111-111111111111');
DELETE FROM service_counter WHERE branch_id IN (SELECT id FROM branch WHERE organization_id = '11111111-1111-1111-1111-111111111111');
DELETE FROM working_hour WHERE branch_id IN (SELECT id FROM branch WHERE organization_id = '11111111-1111-1111-1111-111111111111');
DELETE FROM branch WHERE organization_id = '11111111-1111-1111-1111-111111111111';
DELETE FROM organization WHERE id = '11111111-1111-1111-1111-111111111111';

-- Organization
INSERT INTO organization (id, name, status, created_at, updated_at)
VALUES ('11111111-1111-1111-1111-111111111111', 'Harbor Clinic', 'ACTIVE', now(), now());

-- Branches: Downtown (IST) 2222..., Riverside (NY) 3333...
INSERT INTO branch (id, organization_id, name, city, country, time_zone, status, created_at, updated_at)
VALUES
 ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'Downtown', 'Bhopal', 'India', 'Asia/Kolkata', 'ACTIVE', now(), now()),
 ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', 'Riverside', 'New York', 'USA', 'America/New_York', 'ACTIVE', now(), now());

-- Working hours: every day 09:00-18:00 both branches
INSERT INTO working_hour (id, branch_id, day_of_week, start_time, end_time, created_at, updated_at)
SELECT gen_random_uuid(), b.id::uuid, d.day, '09:00', '18:00', now(), now()
FROM (VALUES ('22222222-2222-2222-2222-222222222222'), ('33333333-3333-3333-3333-333333333333')) AS b(id)
CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY'), ('SATURDAY'), ('SUNDAY')) AS d(day);

-- Services: 4444 consultation Downtown, 5555 dental Downtown, 6666 consultation Riverside
INSERT INTO service (id, branch_id, name, description, standard_duration_minutes, status, created_at, updated_at)
VALUES
 ('44444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222', 'General Consultation', 'Everyday health concerns.', 30, 'ACTIVE', now(), now()),
 ('55555555-5555-5555-5555-555555555555', '22222222-2222-2222-2222-222222222222', 'Dental Checkup', 'Teeth cleaning and review.', 45, 'ACTIVE', now(), now()),
 ('66666666-6666-6666-6666-666666666666', '33333333-3333-3333-3333-333333333333', 'General Consultation', 'Everyday health concerns.', 30, 'ACTIVE', now(), now());

-- Counters: 7777 C1 Downtown, 8888 C2 Downtown, 9999 C1 Riverside
INSERT INTO service_counter (id, branch_id, name, status, created_at, updated_at)
VALUES
 ('77777777-7777-7777-7777-777777777777', '22222222-2222-2222-2222-222222222222', 'Counter 1', 'ACTIVE', now(), now()),
 ('88888888-8888-8888-8888-888888888888', '22222222-2222-2222-2222-222222222222', 'Counter 2', 'ACTIVE', now(), now()),
 ('99999999-9999-9999-9999-999999999999', '33333333-3333-3333-3333-333333333333', 'Counter 1', 'ACTIVE', now(), now());

-- Capabilities: C1 Downtown serves both; C2 serves consultation; Riverside C1 serves its consultation
INSERT INTO counter_capability (id, branch_id, service_id, counter_id, created_at, updated_at)
VALUES
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', '44444444-4444-4444-4444-444444444444', '77777777-7777-7777-7777-777777777777', now(), now()),
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', '55555555-5555-5555-5555-555555555555', '77777777-7777-7777-7777-777777777777', now(), now()),
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', '44444444-4444-4444-4444-444444444444', '88888888-8888-8888-8888-888888888888', now(), now()),
 (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', '66666666-6666-6666-6666-666666666666', '99999999-9999-9999-9999-999999999999', now(), now());

-- Users: aaaa cleo CUSTOMER, bbbb ravi CUSTOMER, cccc sam STAFF, dddd mira MANAGER, eeee adi ADMIN
INSERT INTO app_user (id, organization_id, email, display_name, status, created_at, updated_at)
VALUES
 ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'cleo@example.com', 'Cleo', 'ACTIVE', now(), now()),
 ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111', 'ravi@example.com', 'Ravi', 'ACTIVE', now(), now()),
 ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', 'sam@example.com', 'Sam', 'ACTIVE', now(), now()),
 ('dddddddd-dddd-dddd-dddd-dddddddddddd', '11111111-1111-1111-1111-111111111111', 'mira@example.com', 'Mira', 'ACTIVE', now(), now()),
 ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '11111111-1111-1111-1111-111111111111', 'adi@example.com', 'Adi', 'ACTIVE', now(), now()),
 ('ffffffff-ffff-ffff-ffff-ffffffffffff', '11111111-1111-1111-1111-111111111111', 'dev@example.com', 'Dev', 'ACTIVE', now(), now());

INSERT INTO user_role (user_id, role)
VALUES
 ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'CUSTOMER'),
 ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'CUSTOMER'),
 ('ffffffff-ffff-ffff-ffff-ffffffffffff', 'CUSTOMER'),
 ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'STAFF'),
 ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'MANAGER'),
 ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 'ADMIN');

INSERT INTO user_credential (id, user_id, password_hash, created_at, updated_at)
SELECT gen_random_uuid(), u.id, :'DEMO_HASH', now(), now()
FROM app_user u WHERE u.organization_id = '11111111-1111-1111-1111-111111111111';

-- Organization memberships (Phase 4R.2, ADR-040): the operational grants.
-- Sam STAFF + Mira MANAGER + Adi ADMIN at Harbor Clinic; customers hold no
-- membership rows. Mirrors what the V20 backfill derives for pre-V20 data.
INSERT INTO organization_membership (id, user_id, organization_id, role, status, created_at, updated_at)
VALUES
 (gen_random_uuid(), 'cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', 'STAFF', 'ACTIVE', now(), now()),
 (gen_random_uuid(), 'dddddddd-dddd-dddd-dddd-dddddddddddd', '11111111-1111-1111-1111-111111111111', 'MANAGER', 'ACTIVE', now(), now()),
 (gen_random_uuid(), 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '11111111-1111-1111-1111-111111111111', 'ADMIN', 'ACTIVE', now(), now());

-- Staff assignment: Sam → Downtown ACTIVE
INSERT INTO staff_assignment (id, organization_id, user_id, branch_id, status, created_at, updated_at)
VALUES (gen_random_uuid(), '11111111-1111-1111-1111-111111111111',
 'cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222', 'ACTIVE', now(), now());

-- Queues (one per branch+service used)
INSERT INTO queue (id, organization_id, branch_id, service_id, status, created_at, updated_at)
VALUES
 ('71717171-7171-4717-8717-171717171717', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '44444444-4444-4444-4444-444444444444', 'ACTIVE', now(), now()),
 ('72727272-7272-4727-8727-272727272727', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '55555555-5555-5555-5555-555555555555', 'ACTIVE', now(), now());

-- Appointments (Cleo; non-overlapping per service+customer)
INSERT INTO appointment (id, organization_id, branch_id, service_id, customer_id, start_at, end_at, status, created_at, updated_at)
VALUES
 -- upcoming in ~20h (reminder window) — consultation Downtown
 ('a1111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '44444444-4444-4444-4444-444444444444', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', now() + interval '20 hours', now() + interval '20 hours 30 minutes', 'SCHEDULED', now(), now()),
 -- day after tomorrow — dental Downtown
 ('a2222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '55555555-5555-5555-5555-555555555555', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', now() + interval '44 hours', now() + interval '44 hours 45 minutes', 'SCHEDULED', now(), now()),
 -- past completed
 ('a3333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '44444444-4444-4444-4444-444444444444', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', now() - interval '26 hours', now() - interval '25 hours 30 minutes', 'COMPLETED', now(), now()),
 -- past cancelled
 ('a4444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '44444444-4444-4444-4444-444444444444', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', now() - interval '50 hours', now() - interval '49 hours 30 minutes', 'CANCELLED', now(), now()),
 -- Riverside upcoming (multi-branch view)
 ('a5555555-5555-5555-5555-555555555555', '11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', '66666666-6666-6666-6666-666666666666', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', now() + interval '30 hours', now() + interval '30 hours 30 minutes', 'SCHEDULED', now(), now());

-- Queue entries on q1 (Downtown consultation)
INSERT INTO queue_entry (id, organization_id, queue_id, customer_id, token_number, business_date, priority, status, joined_at, called_at, completed_at, created_at, updated_at)
VALUES
 ('e1111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', '71717171-7171-4717-8717-171717171717', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 1, (now() AT TIME ZONE 'Asia/Kolkata')::date, 'NORMAL', 'WAITING', now() - interval '5 minutes', NULL, NULL, now(), now()),
 ('e2222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', '71717171-7171-4717-8717-171717171717', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 2, (now() AT TIME ZONE 'Asia/Kolkata')::date, 'PRIORITY', 'WAITING', now() - interval '25 minutes', NULL, NULL, now(), now()),
 ('e3333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', '71717171-7171-4717-8717-171717171717', 'ffffffff-ffff-ffff-ffff-ffffffffffff', 3, (now() AT TIME ZONE 'Asia/Kolkata')::date, 'NORMAL', 'SERVING', now() - interval '50 minutes', now() - interval '30 minutes', NULL, now(), now()),
 ('e4444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', '71717171-7171-4717-8717-171717171717', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 4, (now() AT TIME ZONE 'Asia/Kolkata')::date, 'NORMAL', 'COMPLETED', now() - interval '3 hours', now() - interval '2 hours 50 minutes', now() - interval '2 hours 10 minutes', now(), now());

-- Token counter keeps future joins consistent
INSERT INTO queue_token_counter (id, queue_id, business_date, next_number, created_at, updated_at)
VALUES (gen_random_uuid(), '71717171-7171-4717-8717-171717171717', (now() AT TIME ZONE 'Asia/Kolkata')::date, 5, now(), now());

-- Sessions: open one for e3 (Counter 1), ended one for e4 (Counter 2)
INSERT INTO service_session (id, branch_id, queue_entry_id, service_counter_id, service_id, started_at, ended_at, created_at, updated_at)
VALUES
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'e3333333-3333-3333-3333-333333333333', '77777777-7777-7777-7777-777777777777', '44444444-4444-4444-4444-444444444444', now() - interval '15 minutes', NULL, now(), now()),
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'e4444444-4444-4444-4444-444444444444', '88888888-8888-8888-8888-888888888888', '44444444-4444-4444-4444-444444444444', now() - interval '2 hours 40 minutes', now() - interval '2 hours 10 minutes', now(), now());

SELECT 'demo seed ok' AS status;
