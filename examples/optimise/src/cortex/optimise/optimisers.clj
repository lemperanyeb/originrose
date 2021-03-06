(ns cortex.optimise.optimisers
  "Contains protocol extensions for gradient optimisers, as
  well as a selection of sample gradient optimisers for use
  in optimizing pure functions or training neural networks.

  The gradient optimisers usable with the functions in
  cortex.optimise.descent implement the following protocols:

  PParameters - to allow for retrieving an updated parameter vector
  PGradientOptimiser - to allow for passing in parameter and gradient vectors
  PIntrospection - to allow for inspecting the internal state of the optimiser

  In this namespace, the above protocols are extended to Clojure
  maps and Clojure functions. See cortex.optimise.parameters for
  the reason that APersistentMap rather than IPersistentMap is
  used.

  (Note that, for maps, the PParameters protocol is also implemented
  by pure functions, so it is not done here, but rather in the shared
  namespace cortex.optimise.parameters.)

  A Clojure function representing a gradient optimiser must
  take parameter and gradient vectors and return an updated
  parameter vector.

  A Clojure map representing a gradient optimiser must have
  the two keys :initialize and :update, which correspond to
  functions. The :initialize function should take a parameter
  count and return a state map (with, for instance, any accumulation
  vectors initialized to the correct sizes). The :update function
  should take the state map (with the parameter vector under
  the :params key) and a gradient vector, and return a new
  state map with the updated parameter vector under the
  :params key."
  (:refer-clojure :exclude [+ - * /])
  (:require [clojure.core.matrix :as m]
            [clojure.core.matrix.operators :refer [+ - * /]]
            [cortex.optimise.protocols :as cp]
            [cortex.optimise.parameters]))

;;;; Protocol extensions

(defn fn->map
  "Converts a fn optimiser to a map optimiser."
  [function]
  {:update (fn [state gradient]
             (let [state (update state :params function gradient)]
               ;; This makes it much easier to debug a common mistake:
               (when (map? (:params state))
                 (throw (IllegalStateException.
                          "fn acting as optimiser must return vector: did you need to call the fn to produce an optimiser map or fn?")))
               state))})

(extend-type clojure.lang.IFn
  cp/PParameters
  (parameters [this])
  (update-parameters [this params]
    (cp/update-parameters (fn->map this) params))

  cp/PGradientOptimiser
  (compute-parameters [this gradient params]
    (cp/compute-parameters (fn->map this) gradient params))

  cp/PIntrospection
  (get-state [this]))

(extend-type clojure.lang.APersistentMap
  cp/PGradientOptimiser
  (compute-parameters [this gradient parameters]
    (as-> this this
      (if (:initialize this)
        (-> this
          (assoc :state ((:initialize this) (m/ecount parameters)))
          (dissoc :initialize))
        this)
      (-> this
        (assoc-in [:state :params] parameters)
        (update :state (:update this) gradient))))

  cp/PIntrospection
  (get-state [this]
    (dissoc (:state this) :params)))

;;;; Clojure implementations

(defn sgd-clojure
  "Stochastic gradient descent. Steps by the negative gradient
  multiplied by the learning rate. This is technically not
  'stochastic' gradient descent unless the function being optimised
  has mini-batching functionality built in, but 'SGD' is a very
  well-known term so that is what is used for the name of the
  function. See [1].

  [1]: https://en.wikipedia.org/wiki/Stochastic_gradient_descent"
  [& {:keys [learning-rate]
      :or {learning-rate 0.1}}]
  (fn [params gradient]
    (+ params
       (* (- learning-rate)
          gradient))))

(defn accumulate
  "Accumulates a running average, according to the formula:

  E[x]_{t+1} = ??E[x]_t + (1 - ??)x

  where

  ?? => decay-rate
  E[x]_t => running-avg
  x => value
  E[x]_{t+1} => returned"
  [decay-rate running-avg value]
  (+ (* decay-rate running-avg)
     (* (- 1 decay-rate) value)))

(defn adadelta-clojure
  "ADADELTA. Gradient descent algorithm designed to eliminate
  sensitivity to hyperparameter settings. See [1]. The algorithm
  is reproduced below:

  Require: Decay rate ??, Constant ??
  Require: Initial parameter x???
  1: Initialize accumulation variables E[g??]_0 = 0, E[??x??]_0 = 0
  2: for t = 1 : T do %% Loop over # of updates
  3:   Compute Gradient: g_t
  4:   Accumulate Gradient: E[g??]_t = ??E[g??]_{t-1} + (1 - ??)g_t??
  5:   Compute Update: ??x_t = -RMS[??x]_{t-1}/RMS[g]_t g_t
  6:   Accumulate Updates: E[??x??]_t = ??E[??x??]_{t-1} + (1 - ??)??x_t??
  7:   Apply Update: x_{t+1} = x_t + ??x_t
  8: end for

  where

  ?? => decay-rate
  ?? => conditioning
  x => params
  g => gradient
  E[g??] => acc-gradient
  E[??x??] => acc-step

  Note that all vector operations are per-component.

  ADADELTA tends to undergo oscillations and diverge when it gets
  too close to a minimum. See the Mathematica applet for a visual
  demonstration of this behavior.

  [1]: http://arxiv.org/pdf/1212.5701.pdf"
  [& {:keys [decay-rate conditioning]
      :or {decay-rate 0.95
           conditioning 1e-6}}]
  (letfn [(acc [acc-x x]
            (accumulate decay-rate acc-x x))
          (rms [acc-x]
            (m/sqrt
              (+ acc-x
                 conditioning)))]
    {:initialize (fn [param-count]
                   {:acc-gradient (m/new-vector :vectorz param-count)
                    :acc-step (m/new-vector :vectorz param-count)})
     :update (fn [{:keys [params acc-gradient acc-step]} gradient]
               (let [acc-gradient (acc acc-gradient (m/square gradient))
                     step (-> gradient
                            (* (rms acc-step))
                            (/ (rms acc-gradient)))
                     acc-step (acc acc-step (m/square step))
                     params (- params step)]
                 {:acc-gradient acc-gradient
                  :acc-step acc-step
                  :params params}))}))

(defn adam-clojure
  "Adam. Improved combination of AdaGrad and RMSProp. See [2]. The
  algorithm is reproduced below:

  Require: ??: Stepsize
  Require: ?????, ????? ??? [0, 1): Exponential decay rates for the moment estimates
  Require: f(??): Stochastic objective function with parameters ??
  Require: ?????: Initial parameter vector
    m??? = 0 (Initialize 1st moment vector)
    v??? = 0 (Initialize 2nd moment vector)
    t = 0 (Initial timestep)
    while ??_t not converged do
      t = t + 1
      g_t = ???_?? f_t(??_{t-1}) (Get gradients w.r.t. stochastic objective at timestep t)
      m_t = ????? ?? m_{t-1} + (1 - ?????) ?? g_t (Update biased first moment estimate)
      v_t = ????? ?? v_{t-1} + (1 - ?????) ?? g_t?? (Update biased second raw moment estimate)
      m??_t = m_t / (1 - ?????^t) (Compute bias-corrected first moment estimate)
      v??_t = v_t / (1 - ?????^t) (Compute bias-corrected second raw moment estimate)
      ??_t = ??_{t-1} - ?? ?? m??_t / (???(v??_t) + ??) (Update parameters)
    end while
    return ??_t (Resulting parameters)

  where

  ?? => step-size
  ????? => first-moment-decay
  ????? => second-moment-decay
  ?? => conditioning
  t => num-steps
  ?? => params
  g => gradient
  m => first-moment
  v => second-moment
  m?? => first-moment*
  v?? => second-moment*

  Adam appears to perform better than ADADELTA, with both faster convergence and more
  resistance to oscillations, in general.

  [2]: http://arxiv.org/pdf/1412.6980v8.pdf."
  [& {:keys [step-size first-moment-decay
             second-moment-decay conditioning]
      :or {step-size 0.001
           first-moment-decay 0.9
           second-moment-decay 0.999
           conditioning 1e-8}}]
  {:initialize (fn [param-count]
                 {:first-moment (m/new-vector :vectorz param-count)
                  :second-moment (m/new-vector :vectorz param-count)
                  :num-steps 0})
   :update (fn [{:keys [params first-moment second-moment num-steps]} gradient]
             (let [num-steps (inc num-steps)
                   first-moment (accumulate first-moment-decay
                                            first-moment
                                            gradient)
                   second-moment (accumulate second-moment-decay
                                             second-moment
                                             (m/square gradient))
                   first-moment* (/ first-moment
                                    (- 1 (Math/pow first-moment-decay num-steps)))
                   second-moment* (/ second-moment
                                     (- 1 (Math/pow second-moment-decay num-steps)))
                   step (* step-size
                           (/ first-moment*
                              (+ (m/sqrt second-moment*)
                                 conditioning)))
                   params (- params step)]
               {:params params
                :first-moment first-moment
                :second-moment second-moment
                :num-steps num-steps}))})
