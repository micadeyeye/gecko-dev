/* Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

Cu.import("resource://services-sync/engines.js");
Cu.import("resource://services-sync/engines/clients.js");
Cu.import("resource://services-sync/constants.js");
Cu.import("resource://services-sync/policies.js");
Cu.import("resource://services-sync/status.js");

Svc.DefaultPrefs.set("registerEngines", "");
Cu.import("resource://services-sync/service.js");

Engines.register(RotaryEngine);
let engine = Engines.get("rotary");
let tracker = engine._tracker;
engine.enabled = true;

// Tracking info/collections.
let collectionsHelper = track_collections_helper();
let upd = collectionsHelper.with_updated_collection;

function sync_httpd_setup() {
  let handlers = {};

  handlers["/1.1/johndoe/storage/meta/global"] =
    new ServerWBO("global", {}).handler();
  handlers["/1.1/johndoe/storage/steam"] =
    new ServerWBO("steam", {}).handler();

  handlers["/1.1/johndoe/info/collections"] = collectionsHelper.handler;
  delete collectionsHelper.collections.crypto;
  delete collectionsHelper.collections.meta;

  let cr = new ServerWBO("keys");
  handlers["/1.1/johndoe/storage/crypto/keys"] =
    upd("crypto", cr.handler());

  let cl = new ServerCollection();
  handlers["/1.1/johndoe/storage/clients"] =
    upd("clients", cl.handler());

  return httpd_setup(handlers);
}

function setUp() {
  Service.username = "johndoe";
  Service.password = "ilovejane";
  Service.passphrase = "sekrit";
  Service.serverURL = TEST_SERVER_URL;
  Service.clusterURL = TEST_CLUSTER_URL;
  new FakeCryptoService();
}

function run_test() {
  initTestLogging("Trace");

  Log4Moz.repository.getLogger("Sync.Service").level = Log4Moz.Level.Trace;

  run_next_test();
}

add_test(function test_tracker_score_updated() {
  let scoreUpdated = 0;

  function onScoreUpdated() {
    scoreUpdated++;
  }

  Svc.Obs.add("weave:engine:score:updated", onScoreUpdated());

  try {
    do_check_eq(engine.score, 0);

    tracker.score += SCORE_INCREMENT_SMALL;
    do_check_eq(engine.score, SCORE_INCREMENT_SMALL);

    do_check_eq(scoreUpdated, 1);
  } finally {
    Svc.Obs.remove("weave:engine:score:updated", onScoreUpdated);
    tracker.resetScore();
    run_next_test();
  }
});

add_test(function test_sync_triggered() {
  let server = sync_httpd_setup();
  setUp();

  Service.login();

  SyncScheduler.syncThreshold = MULTI_DEVICE_THRESHOLD;
  Svc.Obs.add("weave:service:sync:finish", function onSyncFinish() {
    Svc.Obs.remove("weave:service:sync:finish", onSyncFinish);
    _("Sync completed!");
    server.stop(run_next_test);
  });

  do_check_eq(Status.login, LOGIN_SUCCEEDED);
  tracker.score += SCORE_INCREMENT_XLARGE;
});

add_test(function test_clients_engine_sync_triggered() {
  _("Ensure that client engine score changes trigger a sync.");

  // The clients engine is not registered like other engines. Therefore,
  // it needs special treatment throughout the code. Here, we verify the
  // global score tracker gives it that treatment. See bug 676042 for more.

  let server = sync_httpd_setup();
  setUp();
  Service.login();

  const TOPIC = "weave:service:sync:finish";
  Svc.Obs.add(TOPIC, function onSyncFinish() {
    Svc.Obs.remove(TOPIC, onSyncFinish);
    _("Sync due to clients engine change completed.");
    server.stop(run_next_test);
  });

  SyncScheduler.syncThreshold = MULTI_DEVICE_THRESHOLD;
  do_check_eq(Status.login, LOGIN_SUCCEEDED);
  Clients._tracker.score += SCORE_INCREMENT_XLARGE;
});

add_test(function test_incorrect_credentials_sync_not_triggered() {
  _("Ensure that score changes don't trigger a sync if Status.login != LOGIN_SUCCEEDED.");
  let server = sync_httpd_setup();
  setUp();

  // Ensure we don't actually try to sync.
  function onSyncStart() {
    do_throw("Should not get here!");
  }
  Svc.Obs.add("weave:service:sync:start", onSyncStart);

  // First wait >100ms (nsITimers can take up to that much time to fire, so
  // we can account for the timer in delayedAutoconnect) and then one event
  // loop tick (to account for a possible call to weave:service:sync:start).
  Utils.namedTimer(function() {
    Utils.nextTick(function() {
      Svc.Obs.remove("weave:service:sync:start", onSyncStart);

      do_check_eq(Status.login, LOGIN_FAILED_LOGIN_REJECTED);

      Service.startOver();
      server.stop(run_next_test);
    });
  }, 150, {}, "timer");

  // Faking incorrect credentials to prevent score update.
  Status.login = LOGIN_FAILED_LOGIN_REJECTED;
  tracker.score += SCORE_INCREMENT_XLARGE;
});
