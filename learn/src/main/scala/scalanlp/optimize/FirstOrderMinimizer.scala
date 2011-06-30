package scalanlp.optimize

import scalanlp.util.Logged
import scalanlp.util.Log._
import scalanlp.util.ConsoleLogging
import scalala.tensor._
import scalala.operators.bundles.MutableInnerProductSpace
import scalala.generic.math.CanNorm
import scalala.generic.collection.{CanMapKeyValuePairs, CanViewAsTensor1}
import com.sun.org.apache.xerces.internal.impl.dv.xs.FullDVFactory

/**
 * 
 * @author dlwh
 */

trait FirstOrderMinimizer[T,-DF<:StochasticDiffFunction[T]]
  extends Minimizer[T,DF] with Logged with CheckedConvergence[T] {

  type History;
  case class State(x: T, value: Double,
                   grad: T,
                   adjustedValue: Double,
                   adjustedGradient: T,
                   iter: Int,
                   history: History, failures: Int = 0);


  def iterations(f: DF,init: T): Iterator[State];

  def minimize(f: DF, init: T):T = {
    val steps = iterations(f,init);
     steps.reduceLeft( (a,b) => b).x;
  }
}

object FirstOrderMinimizer {
  case class OptParams(batchSize:Int = 512,
                       regularization: Double = 1.0,
                       alpha: Double = 0.5,
                       maxIterations:Int = -1,
                       useL1: Boolean = false,
                       tolerance:Double = 1E-4,
                       useStochastic: Boolean= false) {

    def minimizer[K,T](f: BatchDiffFunction[T])
                      (implicit arith: MutableInnerProductSpace[Double,T], canNorm: CanNorm[T],
                       TisTensor: CanViewAsTensor1[T,K,Double],
                       TKVPairs: CanMapKeyValuePairs[T,K,Double,Double,T],
                       view:  <:<[T,scalala.tensor.mutable.Tensor1[K,Double] with scalala.tensor.mutable.TensorLike[K, Double, _, T with scalala.tensor.mutable.Tensor1[K,Double]]]): FirstOrderMinimizer[T, BatchDiffFunction[T]] = {
      if(useStochastic) {
        val adjustedRegularization = regularization * 0.01 * batchSize / f.fullRange.size
        this.copy(regularization=adjustedRegularization).minimizer(f.withScanningBatches(batchSize))
      } else {
        minimizer(f:DiffFunction[T])
      }
    }

    def minimizer[K,T](f: StochasticDiffFunction[T])
                      (implicit arith: MutableInnerProductSpace[Double,T], canNorm: CanNorm[T],
                       TisTensor: CanViewAsTensor1[T,K,Double],
                       TKVPairs: CanMapKeyValuePairs[T,K,Double,Double,T],
                       view:  <:<[T,scalala.tensor.mutable.Tensor1[K,Double] with scalala.tensor.mutable.TensorLike[K, Double, _, T with scalala.tensor.mutable.Tensor1[K,Double]]]): FirstOrderMinimizer[T,BatchDiffFunction[T]] = {
      if(regularization == 0.0) {
        new StochasticGradientDescent.SimpleSGD[T](alpha, maxIterations) with ConsoleLogging {
          override val TOLERANCE = tolerance
        }
      } else if(useL1) {
        new AdaptiveGradientDescent.L1Regularization[K,T](regularization, eta=alpha, maxIter = maxIterations)(arith,TisTensor,TKVPairs,canNorm)  with ConsoleLogging {
          override val TOLERANCE = tolerance
        }
      } else { // L2
        new StochasticGradientDescent[T](alpha,  maxIterations) with AdaptiveGradientDescent.L2Regularization[T] with ConsoleLogging {
          override val TOLERANCE = tolerance
          override val lambda = regularization;
        }
      }
    }

    def minimizer[K,T]
      (f: DiffFunction[T])(implicit vspace: MutableInnerProductSpace[Double,T],
                             view:  <:<[T,scalala.tensor.mutable.Tensor1[K,Double] with scalala.tensor.mutable.TensorLike[K, Double, _, T with scalala.tensor.mutable.Tensor1[K,Double]]],
                             canNorm: CanNorm[T]): FirstOrderMinimizer[T,DiffFunction[T]] = {
      if(useL1) new OWLQN[K,T](maxIterations, 5, regularization) with ConsoleLogging {
        override val TOLERANCE = tolerance
      }
      else new LBFGS[T](maxIterations, 5) with ConsoleLogging {
        override val TOLERANCE = tolerance
        override def iterations(f: DiffFunction[T], init: T) = {
          super.iterations(DiffFunction.withL2Regularization(f,regularization),init);
        }
      }
    }
  }
}
