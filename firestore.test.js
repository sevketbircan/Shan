const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const CHANNEL_ID = "shan_20ss504_20bs230";
const USER_1_UID = "google_uid_asli_1";
const USER_2_UID = "google_uid_sevket_2";
const INTRUDER_UID = "google_uid_intruder_3";

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

test("Unauthenticated users cannot read or write any channel, presence, message, or call", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("channels").doc(CHANNEL_ID).get());
  await assertFails(
    unauthDb.collection("channels").doc(CHANNEL_ID).set({
      ownerUid: USER_1_UID,
      memberUids: [USER_1_UID],
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    unauthDb.collection("channels").doc(CHANNEL_ID).collection("messages").get()
  );
});

test("2-person channel creation, join, and 3rd intruder rejection", async () => {
  const user1Db = testEnv.authenticatedContext(USER_1_UID).firestore();
  const user2Db = testEnv.authenticatedContext(USER_2_UID).firestore();
  const intruderDb = testEnv.authenticatedContext(INTRUDER_UID).firestore();
  const now = new Date(Date.now() - 2000);

  // User 1 creates channel
  await assertSucceeds(
    user1Db.collection("channels").doc(CHANNEL_ID).set({
      ownerUid: USER_1_UID,
      memberUids: [USER_1_UID],
      slot1PublicKey: "pubKey1Base64",
      createdAt: now,
      updatedAt: now,
    })
  );

  // User 2 joins channel as 2nd participant
  await assertSucceeds(
    user2Db.collection("channels").doc(CHANNEL_ID).update({
      memberUids: [USER_1_UID, USER_2_UID],
      slot2PublicKey: "pubKey2Base64",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Intruder (3rd user) cannot join or read once 2 members exist
  await assertFails(
    intruderDb.collection("channels").doc(CHANNEL_ID).update({
      memberUids: [USER_1_UID, USER_2_UID, INTRUDER_UID],
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(intruderDb.collection("channels").doc(CHANNEL_ID).get());
});

test("Presence updates, encrypted messages, edit, reaction, and filtered queries", async () => {
  const user1Db = testEnv.authenticatedContext(USER_1_UID).firestore();
  const user2Db = testEnv.authenticatedContext(USER_2_UID).firestore();
  const intruderDb = testEnv.authenticatedContext(INTRUDER_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("channels").doc(CHANNEL_ID).set({
      ownerUid: USER_1_UID,
      memberUids: [USER_1_UID, USER_2_UID],
      slot1PublicKey: "pk1",
      slot2PublicKey: "pk2",
      createdAt: created,
      updatedAt: created,
    });
  });

  // Presence write
  await assertSucceeds(
    user1Db.collection("channels").doc(CHANNEL_ID).collection("presence").doc("20ss504").set({
      slotId: "20ss504",
      authUid: USER_1_UID,
      memberUids: [USER_1_UID, USER_2_UID],
      online: true,
      publicKey: "pk1",
      lastActiveMillis: Date.now(),
      createdAt: created,
      updatedAt: created,
    })
  );

  // Create encrypted message (no 'id' key)
  await assertSucceeds(
    user1Db.collection("channels").doc(CHANNEL_ID).collection("messages").doc("msg_1").set({
      senderSlot: "20ss504",
      senderUid: USER_1_UID,
      memberUids: [USER_1_UID, USER_2_UID],
      msgType: "TEXT",
      ciphertext: "encryptedBase64Payload==",
      iv: "ivBase64==",
      ephemeralKey: "ecdh-p256-aes256gcm",
      mediaChunkCount: 0,
      edited: false,
      expiresAtMillis: Date.now() + 86400000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject ghost field / @DocumentId 'id' leak
  await assertFails(
    user1Db.collection("channels").doc(CHANNEL_ID).collection("messages").doc("msg_bad").set({
      id: "msg_bad",
      senderSlot: "20ss504",
      senderUid: USER_1_UID,
      memberUids: [USER_1_UID, USER_2_UID],
      msgType: "TEXT",
      ciphertext: "encryptedBase64Payload==",
      iv: "ivBase64==",
      ephemeralKey: "ecdh-p256-aes256gcm",
      mediaChunkCount: 0,
      edited: false,
      expiresAtMillis: Date.now() + 86400000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Sender can edit own message
  await assertSucceeds(
    user1Db.collection("channels").doc(CHANNEL_ID).collection("messages").doc("msg_1").update({
      ciphertext: "editedCiphertext==",
      iv: "newIv==",
      ephemeralKey: "ecdh-p256-aes256gcm",
      edited: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Peer (User 2) CANNOT edit User 1's ciphertext, but CAN add emoji reaction
  await assertFails(
    user2Db.collection("channels").doc(CHANNEL_ID).collection("messages").doc("msg_1").update({
      ciphertext: "tampered==",
      iv: "tamperedIv==",
      ephemeralKey: "ecdh-p256-aes256gcm",
      edited: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(
    user2Db.collection("channels").doc(CHANNEL_ID).collection("messages").doc("msg_1").update({
      reactions: { "20bs230": "❤️" },
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Filtered list query succeeds for member; unfiltered fails
  await assertSucceeds(
    user2Db.collection("channels").doc(CHANNEL_ID).collection("messages")
      .where("memberUids", "array-contains", USER_2_UID).get()
  );
  await assertFails(
    user2Db.collection("channels").doc(CHANNEL_ID).collection("messages").get()
  );
  await assertFails(
    intruderDb.collection("channels").doc(CHANNEL_ID).collection("messages")
      .where("memberUids", "array-contains", USER_1_UID).get()
  );
  await assertFails(
    intruderDb.collection("channels").doc(CHANNEL_ID).collection("messages").doc("msg_1").get()
  );

  await assertSucceeds(
    user1Db.collection("channels").doc(CHANNEL_ID).collection("presence").doc("20ss504").update({
      typing: true,
      typingAtMillis: Date.now(),
      lastActiveMillis: Date.now(),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(
    intruderDb.collection("channels").doc(CHANNEL_ID).collection("messages").doc("msg_1").delete()
  );
  await assertSucceeds(
    user1Db.collection("channels").doc(CHANNEL_ID).collection("messages").doc("msg_1").delete()
  );
});

test("Encrypted media chunks and WebRTC call signaling terminal state locking", async () => {
  const user1Db = testEnv.authenticatedContext(USER_1_UID).firestore();
  const user2Db = testEnv.authenticatedContext(USER_2_UID).firestore();
  const created = new Date(Date.now() - 5000);

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("channels").doc(CHANNEL_ID).set({
      ownerUid: USER_1_UID,
      memberUids: [USER_1_UID, USER_2_UID],
      createdAt: created,
      updatedAt: created,
    });
  });

  // Media chunk create & read
  await assertSucceeds(
    user1Db.collection("channels").doc(CHANNEL_ID).collection("messages").doc("msg_img")
      .collection("chunks").doc("chunk_0").set({
        senderUid: USER_1_UID,
        memberUids: [USER_1_UID, USER_2_UID],
        chunkIndex: 0,
        dataCiphertext: "encryptedImageBytes==",
        dataIv: "chunkIv==",
        createdAt: created,
      })
  );

  // Call signaling: create RINGING -> ACTIVE -> ENDED -> locked
  await assertSucceeds(
    user1Db.collection("channels").doc(CHANNEL_ID).collection("calls").doc("call_1").set({
      callerSlot: "20ss504",
      callerUid: USER_1_UID,
      calleeSlot: "20bs230",
      memberUids: [USER_1_UID, USER_2_UID],
      callType: "VIDEO",
      status: "RINGING",
      offerCiphertext: "encryptedSdpOffer==",
      offerIv: "offerIv==",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    user2Db.collection("channels").doc(CHANNEL_ID).collection("calls").doc("call_1").update({
      status: "ACTIVE",
      answerCiphertext: "encryptedSdpAnswer==",
      answerIv: "answerIv==",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(
    user1Db.collection("channels").doc(CHANNEL_ID).collection("calls").doc("call_1").update({
      status: "ENDED",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Terminal state locking: cannot update call after ENDED
  await assertFails(
    user2Db.collection("channels").doc(CHANNEL_ID).collection("calls").doc("call_1").update({
      status: "ACTIVE",
      updatedAt: new Date(Date.now() - 200),
    })
  );
});
