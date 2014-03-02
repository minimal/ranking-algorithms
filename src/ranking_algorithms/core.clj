(ns ranking-algorithms.core
  (:require [ranking-algorithms.ranking :as ranking])
  (:require [ranking-algorithms.glicko :as glicko])
  ;; (:require [ranking-algorithms.parse :as parse])
  ;; (:require [ranking-algorithms.uefa :as uefa])

(defn extract-teams [matches]
  (->> matches
       (mapcat (fn [match] [(:home match) (:away match)]))
       set))

(defn merge-rankings [base-rankings initial-rankings]
  (merge initial-rankings
         (into {} (filter #(contains? initial-rankings (key %)) base-rankings))))

(defn merge-keep-left [left right]
  (select-keys (merge left right) keys left))

(defn rank-teams
  ([matches] (rank-teams matches {}))
  ([matches base-rankings]
     (let [teams-with-rankings
           (merge-rankings
            base-rankings
            (ranking/initial-rankings (extract-teams matches)))]
       (map
        (fn [[team details]]
          [team (read-string (format "%.2f" (:points details)))])
        (sort-by #(:points (val %))
                 >
                 (reduce ranking/process-match teams-with-rankings matches))))))

(defn update-base-rankings [base-rankings matches]
  (reduce ranking/process-match base-rankings matches))

(comment (update-base-rankings (ranking/initial-rankings (extract-teams the-matches)) the-matches))

(defn top-teams
  ([number matches] (top-teams number matches {}))
  ([number matches base-rankings]
      (take number (rank-teams matches base-rankings))))

(defn apply-rounding
  [[ team details]]
  [team (read-string (format "%.2f" (:points details))) (read-string  (format "%.2f" (:rd details)))])

(defn show-opposition [team match]
  (if (= team (:home match))
    {:opposition (:away match) :for (:home_score match) :against (:away_score match) :round (:round match) :date (:date match)}
    {:opposition (:home match) :for (:away_score match) :against (:home_score match) :round (:round match) :date (:date match)}))

(defn show-matches [team matches]
  (->> matches
       (filter #(or (= team (:home %)) (= team (:away %))))
       (map #(show-opposition team %))))

(defn show-opponents [team matches rankings]
  (map #(merge (get rankings (:opposition %)) %)
       (show-matches team matches)))

(defn process-team
  [team ranking matches]
  (let [rankings  (glicko/initial-rankings (extract-teams matches))
        opponents (map glicko/as-glicko-opposition (show-opponents team matches rankings))]
    (-> ranking
        (update-in [:points] #(glicko/ranking-after-round
           { :ranking %
             :ranking-rd (:rd (get rankings team))
             :opponents opponents}))
        (update-in [:rd] #(glicko/rd-after-round
           { :ranking (:points (get rankings team))
             :ranking-rd %
            :opponents opponents})))))

(defn update-team
  [matches rankings updated team]
  (assoc-in updated [team] (process-team team (get rankings team) matches)))

(defn rank-glicko-teams
  ([matches] (rank-glicko-teams matches {}))
  ([matches base-rankings]
     (let [teams-with-rankings
           (merge
                   (glicko/initial-rankings (extract-teams matches)) base-rankings)
           teams
           (extract-teams matches)]
       (map apply-rounding
            (sort-by #(:points (val %))
                     >
                     (reduce (partial update-team matches teams-with-rankings) teams-with-rankings teams))))))

(defn top-glicko-teams
  ([number matches] (top-glicko-teams number matches {}))
  ([number matches base-rankings]
      (take number (rank-glicko-teams matches base-rankings))))

(defn base-ratings [teams]
  (apply array-map
         (flatten (map (fn [[team points]] [team {:points points}]) teams))))

(comment (def base
           (base-ratings (rank-teams (uefa/every-match)))))

(defn performance [opponents]
  (let [last-match (last opponents)]
    (:round last-match)))

(defn match-record [opponents]
  {:wins   (count (filter #(> (:for %) (:against %)) opponents))
   :draw   (count (filter #(= (:for %) (:against %)) opponents))
   :loses  (count (filter #(< (:for %) (:against %)) opponents))})

(defn format-for-printing [all-matches idx [team ranking & [rd]]]
  (let [team-matches (show-matches team all-matches)]
    (merge  {:rank (inc idx)
             :team team
             :ranking ranking
             :rd rd
             :round (performance team-matches)}
            (match-record team-matches))))

(defn print-top-glicko-teams
  [number all-matches base-rankings]
  (clojure.pprint/print-table
   [:rank :team :ranking :rd :round :wins :draw :loses]
   (filter #(not ( nil? (:round %)))
           (map-indexed
            (partial format-for-printing all-matches)
            (top-glicko-teams number all-matches base-rankings)))))

(defn print-top-teams-without-round
  ([number all-matches] (print-top-teams-without-round number all-matches {}))
  ([number all-matches base-rankings]
      (clojure.pprint/print-table
       [:rank :team :ranking :wins :draw :loses]
       (map-indexed
        (partial format-for-printing all-matches)
        (top-teams number all-matches base-rankings)))))

(defn print-top-teams
  ([number all-matches] (print-top-teams number all-matches {}))
  ([number all-matches base-rankings]
      (clojure.pprint/print-table
       [:rank :team :ranking :round :wins :draw :loses]
       (map-indexed
        (partial format-for-printing all-matches)
        (top-teams number all-matches base-rankings)))))


(defn glickoify
  ([matches] (glickoify matches {}))
  ([matches base-rankings]
      (reduce (fn [acc [team points rd]] (assoc-in acc [team] {:points points :rd rd}))
              {}
              (rank-glicko-teams matches base-rankings))))

(defn updated-rds [base-rankings periods-missing]
  (reduce (fn [coll [team _]]
          (update-in coll
                     [team :rd]
                     #(glicko/updated-rd % 100 (get periods-missing team) )))
        base-rankings
        base-rankings))

(comment
  (defn glicko-after
    ([year]
       (glicko-after year (glicko/initial-rankings (extract-teams (uefa/every-match)))))
    ([year base-rankings]
       (let [periods-missed (get uefa/periods-missed-per-season year)
             matches (uefa/all-matches year)]
         (glickoify matches (updated-rds base-rankings periods-missed))))))


(comment (doseq [match (show-matches "Manchester United" all-matches)]
           (println match)))

(comment (defn print-top-teams [number all-matches]
           (doseq [[team details] (ranking-algorithms.core/top-teams number all-matches)]
             (println team
                      details
                      (match-record (show-matches team all-matches))
                      (performance (show-matches team all-matches))))))

