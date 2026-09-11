(ns minimax-m2-modal.openai-test
  (:require [clojure.test :refer [deftest is testing]]
            [minimax-m2-modal.openai :as openai]))

(deftest explicit-configuration
  (testing "URL is required and normalized without consulting ambient state"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"explicit OpenAI-compatible endpoint"
                          (openai/normalize-config {:url "  "})))
    (is (= "https://example.test/v1/chat/completions"
           (openai/endpoint {:url " https://example.test/// "}))))
  (testing "model and key defaults are deterministic closed values"
    (is (= {:url "https://example.test"
            :model "MiniMaxAI/MiniMax-M2.7"
            :api-key "minimax-m2-7-testkey-7f3a"}
           (openai/normalize-config {:url "https://example.test"})))
    (is (= "custom-model"
           (openai/model {:url "https://example.test"
                          :model "custom-model"})))))
