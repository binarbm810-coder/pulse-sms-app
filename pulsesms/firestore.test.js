const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_101";
const BOB_UID = "bob_uid_202";
const CHARLIE_UID = "charlie_uid_303";
const THREAD_ID = `${ALICE_UID}_${BOB_UID}`;
const SHA_ALICE = "a".repeat(64);
const SHA_BOB = "b".repeat(64);
const ts = () => new Date(Date.now() - 1000);

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated caller cannot read users, directory, threads, or payloads", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("users").doc(ALICE_UID).get());
  await assertFails(unauth.collection("user_directory").where("discoverable", "==", true).get());
  await assertFails(unauth.collection("threads").where("participantIds", "array-contains", ALICE_UID).get());
  await assertFails(unauth.collection("photo_payloads").doc("p1").get());
});

test("User profile PII is strictly isolated to owner", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      accountName: "Alice City",
      phoneNumber: "+91 98765 43210",
      phoneNormalized: "919876543210",
      createdAt: ts(),
      updatedAt: ts(),
    })
  );

  // Owner can update their account name
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      accountName: "Alice City 2",
      updatedAt: ts(),
    })
  );

  // Bob cannot read Alice's raw phone profile
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  // Alice cannot inject ghost field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: ts(),
    })
  );
  // Owner cannot mutate immutable userId
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      userId: BOB_UID,
      updatedAt: ts(),
    })
  );
});

test("Directory enforces discoverable filter and blocks cross-user overwrite", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("user_directory").doc(ALICE_UID).set({
      userId: ALICE_UID,
      accountName: "Alice City",
      accountNameLower: "alice city",
      phoneMasked: "•••• 3210",
      phoneSha256: SHA_ALICE,
      discoverable: true,
      createdAt: ts(),
      updatedAt: ts(),
    })
  );

  // Unfiltered directory list fails
  await assertFails(bobDb.collection("user_directory").get());
  // Filtered directory lookup by phoneSha256 + discoverable succeeds
  await assertSucceeds(
    bobDb
      .collection("user_directory")
      .where("discoverable", "==", true)
      .where("phoneSha256", "==", SHA_ALICE)
      .get()
  );
  // Bob cannot hijack Alice's directory card
  await assertFails(
    bobDb.collection("user_directory").doc(ALICE_UID).update({
      accountName: "Hijacked",
      updatedAt: ts(),
    })
  );
});

test("Owner-only contacts subcollection enforces strict ownership", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("contacts").doc(BOB_UID).set({
      userId: ALICE_UID,
      contactUid: BOB_UID,
      accountName: "Bob Village",
      phoneMasked: "•••• 9988",
      phoneSha256: SHA_BOB,
      createdAt: ts(),
      updatedAt: ts(),
    })
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("contacts").get());
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("contacts").get());
});

test("Thread, SMS message, and 15KB WebP photo payload atomic batch + third-party isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  const threadRef = aliceDb.collection("threads").doc(THREAD_ID);
  const photoRef = aliceDb.collection("photo_payloads").doc("photo_1");
  const msgRef = threadRef.collection("messages").doc("msg_1");

  const batch = aliceDb.batch();
  batch.set(threadRef, {
    participantIds: [ALICE_UID, BOB_UID],
    participantNames: ["Alice City", "Bob Village"],
    lastMessagePreview: "Photo (14.2 KB)",
    lastSenderId: ALICE_UID,
    lastMessageAt: ts(),
    createdAt: ts(),
    updatedAt: ts(),
  });
  batch.set(photoRef, {
    senderId: ALICE_UID,
    threadId: THREAD_ID,
    participantIds: [ALICE_UID, BOB_UID],
    webpBase64: "UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAQAcJaQAA3AA/v3AgAA=",
    sizeBytes: 14540,
    width: 320,
    height: 240,
    createdAt: ts(),
  });
  batch.set(msgRef, {
    senderId: ALICE_UID,
    senderName: "Alice City",
    participantIds: [ALICE_UID, BOB_UID],
    type: "photo",
    textBody: "View from city",
    transport: "mqtt_firestore",
    payloadBytes: 128,
    photoPayloadId: "photo_1",
    photoSizeBytes: 14540,
    photoWidth: 320,
    photoHeight: 240,
    createdAt: ts(),
  });

  await assertSucceeds(batch.commit());

  // Bob can query threads & messages & tap to load photo
  await assertSucceeds(
    bobDb.collection("threads").where("participantIds", "array-contains", BOB_UID).get()
  );
  await assertSucceeds(
    bobDb
      .collection("threads")
      .doc(THREAD_ID)
      .collection("messages")
      .where("participantIds", "array-contains", BOB_UID)
      .get()
  );
  await assertSucceeds(bobDb.collection("photo_payloads").doc("photo_1").get());

  // Charlie (non-participant) cannot read existing thread, messages, or photo payload
  await assertFails(charlieDb.collection("threads").doc(THREAD_ID).get());
  await assertFails(
    charlieDb
      .collection("threads")
      .doc(THREAD_ID)
      .collection("messages")
      .where("participantIds", "array-contains", ALICE_UID)
      .get()
  );
  await assertFails(charlieDb.collection("photo_payloads").doc("photo_1").get());

  // Oversized photo > 26KB rejected
  await assertFails(
    aliceDb.collection("photo_payloads").doc("photo_big").set({
      senderId: ALICE_UID,
      threadId: THREAD_ID,
      participantIds: [ALICE_UID, BOB_UID],
      webpBase64: "A".repeat(40001),
      sizeBytes: 30000,
      width: 320,
      height: 240,
      createdAt: ts(),
    })
  );
});
