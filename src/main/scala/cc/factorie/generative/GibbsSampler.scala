/* Copyright (C) 2008-2010 Univ of Massachusetts Amherst, Computer Science Dept
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   This software is provided under the terms of the Eclipse Public License 1.0
   as published by http://www.opensource.org.  For further information,
   see the file `LICENSE.txt' included with this distribution. */

package cc.factorie.generative
import cc.factorie._
import scala.reflect.Manifest 
import scala.collection.mutable.{HashMap, HashSet, PriorityQueue, ArrayBuffer}

// How to think about Proposals and MCMC:
// Variables know their own range of values.  This needs to be coded on a per-variable basis
// Scored preferences about different values are known only by using the model.
// Sometimes we want to sample more than one variable together.  One variable cannot know how to do this on its own.
// Sometimes we want to sample conditioned on other fixed values.  One variable cannot know about this either.  It must be something like a Template
// Sometimes we want sampling to chain: sample v1, then v2 conditioned on the value of v1, etc.
// Making proposals is sometimes keyed by a single variable, a list of variables, or nothing (proposer itself maintains context of what to change next)
// Perhaps proposers should be in a list of Template-like objects; given a variable, first Template in the list to claim it gets to make the change.
// To facilitate ease of use, variable classes could provide something like:
//   class Label[T] { def defaultSampler = LabelSampler; def sample(model:Model) = defaultSampler.sample(this,model) }
//   object LabelSampler extends Sampler1[Label]
    

/** Simple GibbsSampler.
    @author Andrew McCallum */
class GibbsSampler(val model:Model = cc.factorie.generative.defaultGenerativeModel) extends Sampler[Variable] {
  var temperature = 1.0
  val handlers = new ArrayBuffer[GibbsSamplerHandler]
  def defaultHandlers = List(GeneratedVariableGibbsSamplerHandler, MixtureChoiceGibbsSamplerHandler, IterableSettingsGibbsSamplerHandler)
  handlers ++= defaultHandlers
  // TODO Consider Either[] type checking instead of generative Sampler[Variable]
  def process1(v:Variable): DiffList = {
    // Get factors, in sorted order of the their classname
    val factors = model.factors(v).sortWith((f1:Factor,f2:Factor) => f1.getClass.getName < f2.getClass.getName)
    var done = false
    val handlerIterator = handlers.iterator
    val d = newDiffList
    while (!done && handlerIterator.hasNext) {
      done = handlerIterator.next.sample(v, factors, this)(d)
    }
    if (!done) throw new Error("GibbsSampler: No sampling method found for "+factors)
    d
  }
}

trait GibbsSamplerHandler {
  def sample(v:Variable, factors:List[Factor], sampler:GibbsSampler)(implicit d:DiffList): Boolean
}

object GeneratedVariableGibbsSamplerHandler extends GibbsSamplerHandler {
  def sample(v:Variable, factors:List[Factor], sampler:GibbsSampler)(implicit d:DiffList): Boolean = {
    factors match {
      case List(factor:GeneratedVarTemplate#Factor) => {
        v match {
          case v:GeneratedVariable => v.sample(d)
        }
        true
      }
      case _ => false
    }
  }
}

object MixtureChoiceGibbsSamplerHandler extends GibbsSamplerHandler {
  def sample(v:Variable, factors:List[Factor], sampler:GibbsSampler)(implicit d:DiffList): Boolean = {
    //return false
    v match {
      case mc:MixtureChoiceVariable => {
        // TODO We really should have a more careful check like this
        //if (!(factors.forall(_ match { case f:GeneratedVarTemplate#Factor => f.n1 == v || f.n2 == v; case _ => false }))) return false
        val outcomes: Seq[MixtureOutcome] = mc.outcomes
        val domainSize = mc.domainSize
        val distribution = new Array[Double](domainSize)
        var sum = 0.0
        for (i <- 0 until domainSize) {
          distribution(i) = mc.proportions.pr(i) * outcomes.foldLeft(1.0)((prod:Double, outcome:MixtureOutcome) => prod * outcome.prFromMixtureComponent(i))
          sum += distribution(i)
        }
        mc.set(Maths.nextDiscrete(distribution, sum)(cc.factorie.random))(d)
        true
      }
    }
  }
}

object IterableSettingsGibbsSamplerHandler extends GibbsSamplerHandler {
  def sample(v:Variable, factors:List[Factor], sampler:GibbsSampler)(implicit d:DiffList): Boolean = {
    v match {
      case v2: Variable with IterableSettings => {
        // Iterate over all settings of the variable 'v', score each change, and sample from those scores
        val proposals = v2.settings.map(d => {val m = d.scoreAndUndo(sampler.model); new Proposal(d, m, Math.NaN_DOUBLE, m/sampler.temperature)}).toList
        val proposal = proposals.sampleExpProportionally((p:Proposal) => p.acceptanceScore)
        proposal.diff.redo
        if (d ne null) d ++= proposal.diff
        true
      }
      case _ =>  false
    }
  }
}
