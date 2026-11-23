// Release tests for database.rules.json: every group-ride feature's reads and writes, made the same way
// the app makes them (see shared/.../group/GroupClient.kt), checked against the Realtime Database emulator.
// Run with `npm test` in this folder (it starts the emulator). Test names start with the feature they cover;
// release/features.txt lists which tests each feature needs.
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { setLogLevel } from 'firebase/app';
import { get, push, ref, remove, set, update } from 'firebase/database';

setLogLevel('silent'); // expected permission_denied warnings would bury the results

const CODE = 'K7QM2P';
const G = `groups/${CODE}`;
const MIN = 60_000;
let env;

const db = (uid) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).database();
const member = (name, role, slot) => ({ name, role, joinedAt: Date.now(), slot: String(slot) });
const seed = (data) => env.withSecurityRulesDisabled((c) => set(ref(c.database()), data));

/** A group led by "lead" with riders "r2" and "r3", as the app leaves it after create + two joins. */
function groupOf(extra = {}) {
  return {
    meta: { name: 'Goa trial', leader: 'lead', createdAt: Date.now() },
    slots: { 1: 'lead', 2: 'r2', 3: 'r3' },
    members: { lead: member('Asha', 'leader', 1), r2: member('Ravi', 'rider', 2), r3: member('Meera', 'rider', 3) },
    ...extra,
  };
}

/** What GroupClient.takeSeat writes when [uid] joins in seat [n]. */
const joinWrite = (uid, n, role = 'rider', more = {}) => ({ [`slots/${n}`]: uid, [`members/${uid}`]: member(uid, role, n), ...more });

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-sawaari',
    database: { rules: readFileSync(new URL('../database.rules.json', import.meta.url), 'utf8'), host: '127.0.0.1', port: 9000 },
  });
});
after(() => env.cleanup());
beforeEach(() => env.clearDatabase());

describe('Group create and join', () => {
  test('group: leader creates a group and takes seat 1', async () => {
    const d = db('lead');
    await assertSucceeds(set(ref(d, `${G}/meta`), { name: 'Goa trial', leader: 'lead', createdAt: Date.now() }));
    await assertSucceeds(update(ref(d, G), joinWrite('lead', 1, 'leader')));
  });

  test('group: nobody can create a group in someone else\'s name', async () => {
    await assertFails(set(ref(db('x'), `${G}/meta`), { name: 'Fake', leader: 'lead', createdAt: 1 }));
  });

  test('group: a rider joins with the code and takes the next free seat', async () => {
    await seed({ groups: { [CODE]: { meta: groupOf().meta, slots: { 1: 'lead' }, members: { lead: member('Asha', 'leader', 1) } } } });
    await assertSucceeds(update(ref(db('r2'), G), joinWrite('r2', 2)));
  });

  test('group: a joining rider cannot take a seat someone already holds', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(update(ref(db('r4'), G), joinWrite('r4', 2)));
  });

  test('group: a joining rider cannot make themselves leader', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(update(ref(db('r4'), G), joinWrite('r4', 4, 'leader')));
  });

  test('group: joining needs a seat', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(set(ref(db('r4'), `${G}/members/r4`), { name: 'Sneaky', role: 'rider', joinedAt: 1 }));
  });

  test('group: names longer than 40 characters are refused', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(update(ref(db('r4'), G), { 'slots/4': 'r4', 'members/r4': { ...member('x', 'rider', 4), name: 'n'.repeat(41) } }));
  });

  test('group: anyone signed in can check a code exists, only riders in the group see the ride', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(get(ref(db('stranger'), `${G}/meta`)));
    await assertFails(get(ref(db('stranger'), G)));
    await assertSucceeds(get(ref(db('r2'), G)));
    await assertFails(get(ref(db(null), `${G}/meta`)));
  });

  test('group: a rider can leave and free their seat', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    const d = db('r2');
    await assertSucceeds(remove(ref(d, `${G}/members/r2`)));
    await assertSucceeds(remove(ref(d, `${G}/slots/2`)));
  });

  test('group: the leader can remove a rider, other riders cannot', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(update(ref(db('r3'), G), { 'members/r2': null }));
    await assertSucceeds(update(ref(db('lead'), G), { 'locations/r2': null, 'gear/r2': null, 'members/r2': null }));
    await assertSucceeds(remove(ref(db('lead'), `${G}/slots/2`)));
  });
});

describe('Plans: pricing and rider limit', () => {
  const pro = (uid) => ({ [uid]: { until: Date.now() + 30 * 24 * 60 * MIN } });

  test('plans: with pricing off (Goa trial), a 4th and a 10th rider join for free', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(update(ref(db('r4'), G), joinWrite('r4', 4)));
    await assertSucceeds(update(ref(db('r10'), G), joinWrite('r10', 10)));
  });

  test('plans: with pricing on, a 4th rider without Pro is refused', async () => {
    await seed({ config: { pricing: true }, groups: { [CODE]: groupOf() } });
    await assertFails(update(ref(db('r4'), G), joinWrite('r4', 4)));
  });

  test('plans: with pricing on, a 4th rider joins once everyone has Pro', async () => {
    await seed({ config: { pricing: true }, subs: { ...pro('lead'), ...pro('r2'), ...pro('r3'), ...pro('r4') }, groups: { [CODE]: groupOf() } });
    await assertSucceeds(update(ref(db('r4'), G), joinWrite('r4', 4, 'rider', { pro: true })));
  });

  test('plans: with pricing on, the group cannot turn Pro while a first-three rider lacks Pro', async () => {
    await seed({ config: { pricing: true }, subs: { ...pro('lead'), ...pro('r3'), ...pro('r4') }, groups: { [CODE]: groupOf() } });
    await assertFails(update(ref(db('r4'), G), joinWrite('r4', 4, 'rider', { pro: true })));
  });

  test('plans: only the server can write subscriptions and the pricing switch', async () => {
    await assertFails(set(ref(db('r2'), 'subs/r2/until'), Date.now() + MIN));
    await assertFails(set(ref(db('r2'), 'config/pricing'), false));
    await assertSucceeds(get(ref(db('r2'), 'config')));
  });
});

describe('Leader, sweep and roles', () => {
  test('roles: the leader hands over the lead', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(update(ref(db('lead'), G), { 'meta/leader': 'r2', 'members/r2/role': 'leader', 'members/lead/role': 'rider' }));
  });

  test('roles: a rider cannot take the lead while the leader is fine', async () => {
    await seed({ groups: { [CODE]: groupOf({ locations: { lead: { lat: 15.5, lon: 73.8, ts: Date.now() } } }) } });
    await assertFails(update(ref(db('r2'), G), { 'meta/leader': 'r2', 'members/r2/role': 'leader', 'members/lead/role': 'rider' }));
  });

  test('roles: a rider takes over when the leader sent an SOS', async () => {
    const g = groupOf();
    g.members.lead.sosAt = Date.now() - 5 * MIN;
    await seed({ groups: { [CODE]: g } });
    await assertSucceeds(update(ref(db('r2'), G), { 'meta/leader': 'r2', 'members/r2/role': 'leader', 'members/lead/role': 'rider' }));
  });

  test('roles: a rider takes over when the leader lost signal for over 10 minutes', async () => {
    await seed({ groups: { [CODE]: groupOf({ locations: { lead: { lat: 15.5, lon: 73.8, ts: Date.now() - 11 * MIN } } }) } });
    await assertSucceeds(update(ref(db('r2'), G), { 'meta/leader': 'r2', 'members/r2/role': 'leader', 'members/lead/role': 'rider' }));
  });

  test('roles: the leader makes riders sweep, marshal or medic; riders cannot', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(set(ref(db('lead'), `${G}/members/r2/role`), 'sweep'));
    await assertSucceeds(set(ref(db('lead'), `${G}/members/r3/role`), 'medic'));
    await assertSucceeds(set(ref(db('lead'), `${G}/members/r3/role`), 'marshal'));
    await assertFails(set(ref(db('r2'), `${G}/members/r3/role`), 'rider'));
    await assertFails(set(ref(db('lead'), `${G}/members/r3/role`), 'captain'));
  });
});

describe('Shared route, meeting point and schedule', () => {
  const plan = () => ({ end: { lat: 15.49, lon: 73.82 }, stops: [{ lat: 15.3, lon: 74.0 }], start: { lat: 15.0, lon: 74.1 }, by: 'Asha', ts: Date.now() });
  const schedule = () => ({
    start: { at: Date.now() + 60 * MIN, name: 'Panaji' },
    stops: { 0: { at: Date.now() + 120 * MIN, name: 'Fuel stop' } },
    end: { at: Date.now() + 240 * MIN },
    by: 'Asha', ts: Date.now(),
  });

  test('route: the leader shares the planned route, riders cannot change it', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(set(ref(db('lead'), `${G}/plan`), plan()));
    await assertFails(set(ref(db('r2'), `${G}/plan`), plan()));
    await assertSucceeds(remove(ref(db('lead'), `${G}/plan`)));
  });

  test('route: a shared route has at most 8 stops and needs a destination', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    const tooMany = { ...plan(), stops: Array.from({ length: 9 }, () => ({ lat: 15, lon: 74 })) };
    await assertFails(set(ref(db('lead'), `${G}/plan`), tooMany));
    await assertFails(set(ref(db('lead'), `${G}/plan`), { stops: [], ts: Date.now() }));
  });

  test('meet: the leader sets a meeting point with a note up to 120 characters', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    const meet = { lat: 15.5, lon: 73.8, time: 0, note: 'Petrol pump', by: 'Asha', ts: Date.now() };
    await assertSucceeds(set(ref(db('lead'), `${G}/meet`), meet));
    await assertFails(set(ref(db('r2'), `${G}/meet`), meet));
    await assertFails(set(ref(db('lead'), `${G}/meet`), { ...meet, note: 'x'.repeat(121) }));
  });

  test('schedule: the leader saves times for start, stops and destination', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(set(ref(db('lead'), `${G}/schedule`), schedule()));
    await assertSucceeds(remove(ref(db('lead'), `${G}/schedule`)));
  });

  test('schedule: riders cannot change the schedule', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(set(ref(db('r2'), `${G}/schedule`), schedule()));
  });

  test('schedule: stop names are at most 40 characters and stop numbers 0 to 7', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(set(ref(db('lead'), `${G}/schedule`), { ...schedule(), start: { at: Date.now(), name: 'n'.repeat(41) } }));
    await assertFails(set(ref(db('lead'), `${G}/schedule`), { ...schedule(), stops: { 8: { at: Date.now() } } }));
  });
});

describe('Start and end the ride', () => {
  const ride = (state, uid, at = Date.now()) => ({ state, at, by: 'Ravi', uid });

  test('ride: any rider in the group can start and end the ride', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(set(ref(db('r2'), `${G}/ride`), ride('started', 'r2')));
    await assertSucceeds(set(ref(db('r3'), `${G}/ride`), ride('ended', 'r3')));
    await assertSucceeds(set(ref(db('lead'), `${G}/ride`), ride('started', 'lead')));
  });

  test('ride: someone outside the group cannot start it', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(set(ref(db('stranger'), `${G}/ride`), ride('started', 'stranger')));
  });

  test('ride: a rider cannot start it in another rider\'s name', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(set(ref(db('r2'), `${G}/ride`), ride('started', 'r3')));
  });

  test('ride: only started or ended, and not timed in the future', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(set(ref(db('r2'), `${G}/ride`), ride('paused', 'r2')));
    await assertFails(set(ref(db('r2'), `${G}/ride`), ride('started', 'r2', Date.now() + 10 * MIN)));
  });
});

describe('Packing checklist sharing', () => {
  test('packing: a rider shares their chosen items with the group', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(set(ref(db('r2'), `${G}/gear/r2`), { d7: 'Puncture repair kit', d11: 'First-aid kit' }));
    await assertSucceeds(get(ref(db('r3'), `${G}/gear`)));
    await assertSucceeds(remove(ref(db('r2'), `${G}/gear/r2`)));
  });

  test('packing: nobody can change another rider\'s shared items', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(set(ref(db('r3'), `${G}/gear/r2`), { d7: 'Puncture repair kit' }));
    await assertFails(set(ref(db('stranger'), `${G}/gear/stranger`), { d7: 'Puncture repair kit' }));
  });

  test('packing: item names are at most 40 characters with simple ids', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(set(ref(db('r2'), `${G}/gear/r2`), { d7: 'n'.repeat(41) }));
    await assertFails(set(ref(db('r2'), `${G}/gear/r2`), { 'bad id!': 'Kit' }));
  });
});

describe('Live locations and alerts', () => {
  const loc = () => ({ lat: 15.5, lon: 73.8, speed: 12, bearing: 90, ts: Date.now() });

  test('location: a rider shares their own live location', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(set(ref(db('r2'), `${G}/locations/r2`), loc()));
  });

  test('location: nobody can post another rider\'s location, or post from outside the group', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(set(ref(db('r3'), `${G}/locations/r2`), loc()));
    await assertFails(set(ref(db('stranger'), `${G}/locations/stranger`), loc()));
    await assertFails(set(ref(db('r2'), `${G}/locations/r2`), { lat: 15.5, lon: 73.8 }));
  });

  test('alerts: a rider takes a break and reports a reroute', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(set(ref(db('r2'), `${G}/members/r2/breakAt`), Date.now()));
    await assertSucceeds(remove(ref(db('r2'), `${G}/members/r2/breakAt`)));
    await assertSucceeds(set(ref(db('r2'), `${G}/members/r2/rerouteAt`), Date.now()));
    await assertFails(set(ref(db('r2'), `${G}/members/r2/breakAt`), Date.now() + 10 * MIN));
  });

  test('contacts: a rider shares a phone and emergency contact within size limits', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(update(ref(db('r2'), G), { 'members/r2/phone': '+919800000000', 'members/r2/iceName': 'Mom', 'members/r2/icePhone': '+919800000001' }));
    await assertFails(set(ref(db('r2'), `${G}/members/r2/phone`), '9'.repeat(21)));
    await assertFails(set(ref(db('r3'), `${G}/members/r2/phone`), '+919800000002'));
  });
});

describe('SOS to the group', () => {
  test('sos: a rider alerts the group and is marked in trouble', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    const d = db('r2');
    await assertSucceeds(push(ref(d, `${G}/alerts`), { uid: 'r2', name: 'Ravi', ts: Date.now(), lat: 15.5, lon: 73.8 }));
    await assertSucceeds(set(ref(d, `${G}/members/r2/sosAt`), Date.now()));
  });

  test('sos: nobody can raise an SOS in another rider\'s name or from outside the group', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(push(ref(db('r3'), `${G}/alerts`), { uid: 'r2', name: 'Ravi', ts: Date.now() }));
    await assertFails(push(ref(db('stranger'), `${G}/alerts`), { uid: 'stranger', name: 'X', ts: Date.now() }));
  });

  test('sos: an alert cannot be edited or deleted once sent', async () => {
    await seed({ groups: { [CODE]: groupOf({ alerts: { a1: { uid: 'r2', name: 'Ravi', ts: Date.now() } } }) } });
    await assertFails(set(ref(db('r2'), `${G}/alerts/a1`), { uid: 'r2', name: 'Ravi', ts: Date.now() + 1 }));
    await assertFails(remove(ref(db('lead'), `${G}/alerts/a1`)));
  });
});

describe('Group chat and voice notes', () => {
  const text = (uid, t = 'Fuel stop') => ({ uid, name: 'Ravi', ts: Date.now(), text: t });

  test('chat: riders send and read messages', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertSucceeds(push(ref(db('r2'), `chats/${CODE}`), text('r2')));
    await assertSucceeds(get(ref(db('r3'), `chats/${CODE}`)));
    await assertFails(get(ref(db('stranger'), `chats/${CODE}`)));
  });

  test('chat: no messages from outside the group, in another\'s name, or over 500 characters', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    await assertFails(push(ref(db('stranger'), `chats/${CODE}`), text('stranger')));
    await assertFails(push(ref(db('r3'), `chats/${CODE}`), text('r2')));
    await assertFails(push(ref(db('r2'), `chats/${CODE}`), text('r2', 'x'.repeat(501))));
  });

  test('chat: a sent message cannot be edited', async () => {
    await seed({ groups: { [CODE]: groupOf() }, chats: { [CODE]: { m1: text('r2') } } });
    await assertFails(set(ref(db('r2'), `chats/${CODE}/m1`), text('r2', 'changed')));
  });

  test('voice: a rider sends a voice note up to 30 seconds', async () => {
    await seed({ groups: { [CODE]: groupOf() } });
    const d = db('r2');
    await assertSucceeds(set(ref(d, `chats/${CODE}/v1`), { uid: 'r2', name: 'Ravi', ts: Date.now(), voiceMs: 30_000 }));
    await assertSucceeds(set(ref(d, `chatVoice/${CODE}/v1`), 'AAAA'.repeat(1000)));
    await assertSucceeds(get(ref(db('r3'), `chatVoice/${CODE}/v1`)));
    await assertFails(set(ref(d, `chats/${CODE}/v2`), { uid: 'r2', name: 'Ravi', ts: Date.now(), voiceMs: 40_000 }));
    await assertFails(set(ref(d, `chatVoice/${CODE}/v3`), 'A'.repeat(125_001)));
  });
});

describe('Place ratings', () => {
  test('places: a rider rates a place 1 to 5 stars in their own name', async () => {
    await assertSucceeds(set(ref(db('r2'), 'places/p1/r2'), 4));
    await assertFails(set(ref(db('r2'), 'places/p1/r2'), 6));
    await assertFails(set(ref(db('r2'), 'places/p1/r3'), 4));
    await assertSucceeds(get(ref(db('r3'), 'places/p1')));
  });
});
