(ns de.otto.tesla.system-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as c]
            [de.otto.tesla.util.test-utils :as u]
            [de.otto.tesla.system :as system]
            [ring.mock.request :as mock]
            [de.otto.tesla.stateful.handler :as handler]
            [de.otto.tesla.stateful.configuring :as configuring]
            [environ.core :as env]
            [overtone.at-at :as at]
            [de.otto.tesla.stateful.scheduler :as scheduler]))

(deftest ^:unit should-start-base-system-and-shut-it-down
  (testing "start then shutdown using own method"
    (let [started (system/start (system/base-system {}))
          _ (system/stop started)]
      (is (= "look ma, no exceptions" "look ma, no exceptions"))))

  (testing "start then shutdown using method from library"
    (let [started (system/start (system/base-system {}))
          _ (c/stop started)]
      (is (= "look ma, no exceptions" "look ma, no exceptions")))))

(deftest should-lock-application-on-shutdown
  (testing "the lock is set"
    (u/with-started
      [started (system/base-system {:wait-ms-on-stop 10})]
      (let [healthcomp (:health started)
            _ (system/stop started)]
        (is (= @(:locked healthcomp) true)))))

  (testing "it waits on stop"
    (u/with-started
      [started (system/base-system {:wait-seconds-on-stop 1})]
      (let [has-waited (atom false)]
        (with-redefs [system/wait! (fn [_] (reset! has-waited true))]
          (let [healthcomp (:health started)
                _ (system/stop started)]
            (is (= @(:locked healthcomp) true))
            (is (= @has-waited true))))))))


(deftest ^:integration should-substitute-env-variables-while-reading
  (with-redefs [env/env {:my-custom-status-url "/custom/status/path" :prop-without-fallback "some prop value"}]
    (u/with-started [started (system/base-system {})]
                    (testing "should load the status-path property from edn"
                      (is (= "/custom/status/path"
                             (:status-url (configuring/load-config-from-edn-files)))))

                    (testing "should point to edn-configured custom status url"
                      (let [handlers (handler/handler (:handler started))
                            response (handlers (mock/request :get "/custom/status/path"))]
                        (is (= 200 (:status response)))))))

  (u/with-started [started (system/base-system {})]
                  (testing "should fallback to default for status path"
                    (is (= "/status"
                           (:status-url (configuring/load-config-from-edn-files)))))))

(deftest the-scheduler-in-the-base-system
  (testing "should schedule and execute task NOW"
    (u/with-started [started (system/base-system {})]
                    (let [work-done (atom :no-work-done)
                          {:keys [scheduler]} started]
                      (at/after 0 #(reset! work-done :work-done!) (scheduler/pool scheduler))
                      (Thread/sleep 10)
                      (is (= :work-done! @work-done))))))

(defrecord SingleRoute [rname single-route]
  c/Lifecycle
  (start [self]
    (handler/register-handler (:handler self) rname single-route)
    self)
  (stop [self] self))

(defn test-route [id]
  (c/using (->SingleRoute (str "route-" id) (fn [{:keys [uri]}]
                                              (when (= uri (str "/route-" id))
                                                :ping))) [:handler]))

(deftest request-timing
  (testing "should time and report request for handler"
    (let [reportings (atom [])]
      (with-redefs [handler/time-taken (constantly 100)
                    handler/report-request-timings! (fn [_ handler-name time-taken] (swap! reportings conj [handler-name time-taken]))]
        (u/with-started [started (-> (system/base-system {:handler {:report-timings? true}})
                                     (assoc
                                       :route0 (test-route 0)
                                       :route1 (test-route 1)
                                       :route2 (test-route 2)
                                       :route3 (test-route 3)))]
                        (let [single-handler-fn (handler/handler (:handler started))]
                          (single-handler-fn (mock/request :get "/route-0"))
                          (single-handler-fn (mock/request :get "/route-1"))
                          (single-handler-fn (mock/request :get "/route-2"))
                          (single-handler-fn (mock/request :get "/route-3"))
                          (single-handler-fn (mock/request :get "/route-3"))
                          (single-handler-fn (mock/request :get "/route-3"))
                          (is (= [["route-0" 100]
                                  ["route-1" 100]
                                  ["route-2" 100]
                                  ["route-3" 100]
                                  ["route-3" 100]
                                  ["route-3" 100]] @reportings))))))))
