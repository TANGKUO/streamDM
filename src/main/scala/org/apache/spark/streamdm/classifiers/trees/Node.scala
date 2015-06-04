/*
 * Copyright (C) 2015 Holmes Team at HUAWEI Noah's Ark Lab.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.spark.streamdm.classifiers.trees

import scala.collection.mutable.ArrayBuffer
import scala.math.{ max }

import org.apache.spark.streamdm.core.Example
import org.apache.spark.streamdm.classifiers.bayes._
import org.apache.spark.streamdm.util.Util

/**
 * class Node for hoeffding Tree
 */
abstract class Node(val classDistribution: Array[Double]) extends Serializable {

  var dep: Int = 0
  // stores class distribution of a block of RDD
  val blockClassDistribution: Array[Double] = new Array[Double](classDistribution.length)

  /*
   * filter the data to the related leaf node
   */
  def filterToLeaf(example: Example, parent: SplitNode, index: Int): FoundNode

  /*
   * return the class distribution
   */
  def classVotes(ht: HoeffdingTreeModel, example: Example): Array[Double] = classDistribution.clone()

  /*
   * whether a node is a leaf
   */
  def isLeaf(): Boolean = true
  /*
   * depth of current node in the tree
   */
  def depth() = dep

  def setDepth(depth: Int): Unit = {
    dep = depth
    if (this.isInstanceOf[SplitNode]) {
      val splidNode = this.asInstanceOf[SplitNode]
      splidNode.children.foreach { _.setDepth(depth + 1) }
    }
  }

  /*
   * merge the two node
   * 
   * @param node the node which will be merged
   * @param trySplit whether to split the current node
   * @return current node
   */
  def merge(that: Node, trySplit: Boolean): Node

  /*
   * number of children
   */
  def numChildren(): Int = 0

  /*
   * node description
   */
  def description(): String = { "  " * dep + "Leaf" + " weight = " + Util.arraytoString(classDistribution) + "\n" }

}

/**
 * class FoundNode is the container of a node and has an index and a link to parent
 */
class FoundNode(val node: Node, val parent: SplitNode, val index: Int) extends Serializable {

}
/**
 * class SplitNode is a branch node for Hoeffding Tree
 */
class SplitNode(classDistribution: Array[Double], val conditionalTest: ConditionalTest)
  extends Node(classDistribution) with Serializable {

  val children: ArrayBuffer[Node] = new ArrayBuffer[Node]()

  def this(that: SplitNode) {
    this(Util.mergeArray(that.classDistribution, that.blockClassDistribution), that.conditionalTest)
  }

  /*
   * filter the data to the related leaf node
   */
  override def filterToLeaf(example: Example, parent: SplitNode, index: Int): FoundNode = {
    val cIndex = childIndex(example)
    if (cIndex >= 0) {
      if (cIndex < children.length && children(cIndex) != null) {
        children(cIndex).filterToLeaf(example, this, cIndex)
      } else new FoundNode(null, this, cIndex)
    } else new FoundNode(this, parent, index)
  }

  def childIndex(example: Example): Int = {
    conditionalTest.branch(example)
  }

  def setChild(index: Int, node: Node): Unit = {
    if (children.length > index) {
      children(index) = node
      node.setDepth(dep + 1)
    } else if (children.length == index) {
      children.append(node)
      node.setDepth(dep + 1)
    } else {
      assert(children.length < index)
    }
  }
  /*
   * whether a node is a leaf
   */
  override def isLeaf() = false
  /*
   * number of children
   */
  override def numChildren(): Int = children.filter { _ != null }.length

  /*
   * merge the two node
   * 
   * @param node the node which will be merged
   * @param trySplit whether to split the current node
   * @return current node
   */
  override def merge(that: Node, trySplit: Boolean): Node = {
    if (!that.isInstanceOf[SplitNode]) this
    else {
      val splitNode = that.asInstanceOf[SplitNode]
      for (i <- 0 until children.length)
        this.children(i) = (this.children(i)).merge(splitNode.children(i), trySplit)
      this
    }
  }

  /*
   * node description
   */
  override def description(): String = {
    val sb = new StringBuffer("  " * dep + "\n")
    val testDes = conditionalTest.description()
    for (i <- 0 until children.length) {
      sb.append("  " * dep + " if " + testDes(i) + "\n")
      sb.append("  " * dep + children(i).description())
    }
    sb.toString()
  }

  override def toString(): String = "level[" + dep + "] SplitNode"

}
/**
 * class learning node for Hoeffding Tree
 */
abstract class LearningNode(classDistribution: Array[Double]) extends Node(classDistribution) with Serializable {

  /*
   * lean and update the node
   */
  def learn(ht: HoeffdingTreeModel, example: Example): Unit

  /*
   * whether a learning node is active
   */
  def isActive(): Boolean

  /*
   * filter the data to the related leaf node
   */
  override def filterToLeaf(example: Example, parent: SplitNode, index: Int): FoundNode = new FoundNode(this, parent, index)

}

/**
 * basic majority class active learning node for hoeffding tree
 */
class ActiveLearningNode(classDistribution: Array[Double], val featureTypeArray: FeatureTypeArray)
  extends LearningNode(classDistribution) with Serializable {

  var addonWeight: Double = 0

  val featureObservers: Array[FeatureClassObserver] = new Array[FeatureClassObserver](featureTypeArray.numFeatures)

  def this(that: ActiveLearningNode) {
    this(Util.mergeArray(that.classDistribution, that.blockClassDistribution), that.featureTypeArray)
    init()
  }
  def init(): Unit = {
    if (featureObservers(0) == null) {
      featureTypeArray.featureTypes.zipWithIndex.foreach(x => featureObservers(x._2) =
        FeatureClassObserver.createFeatureClassObserver(x._1, classDistribution.length, x._2, x._1.getRange()))
    }
  }
  /*
   * lean and update the node
   */
  override def learn(ht: HoeffdingTreeModel, example: Example): Unit = {
    init()
    blockClassDistribution(example.labelAt(0).toInt) += example.weight
    featureObservers.zipWithIndex.foreach {
      x => x._1.observeClass(example.labelAt(0).toInt, example.featureAt(x._2), example.weight)
    }
  }

  /*
   * whether a node is active.
   */
  override def isActive(): Boolean = true

  def isPure(): Boolean = {
    val sb1 = new StringBuffer()
    val sb2 = new StringBuffer()
    for (i <- 0 until classDistribution.length) {
      sb1.append(classDistribution(i) + "\t")
      sb2.append(blockClassDistribution(i) + "\t")
    }
    this.classDistribution.filter(_ > 0).length <= 1 &&
      this.blockClassDistribution.filter(_ > 0).length <= 1
  }

  def weight(): Double = { classDistribution.sum + blockClassDistribution.sum }

  def blockWeight(): Double = blockClassDistribution.sum

  def addOnWeight(): Double = {
    if (blockWeight() != 0) blockWeight()
    else addonWeight
  }

  /*
   * merge the two node
   * 
   * @param node the node which will be merged
   * @param trySplit whether to split the current node
   * @return current node
   */
  override def merge(that: Node, trySplit: Boolean): Node = {
    if (that.isInstanceOf[ActiveLearningNode]) {
      val node = that.asInstanceOf[ActiveLearningNode]
      //merge addonWeight and class distribution
      if (!trySplit) {
        this.addonWeight += that.blockClassDistribution.sum
        for (i <- 0 until blockClassDistribution.length)
          this.classDistribution(i) += that.blockClassDistribution(i)
      } else {
        this.addonWeight += node.addonWeight
        for (i <- 0 until classDistribution.length)
          this.classDistribution(i) += that.classDistribution(i)
      }
      //merge feature class observers
      for (i <- 0 until featureObservers.length)
        featureObservers(i) = featureObservers(i).merge(node.featureObservers(i), trySplit)
    }
    this
  }

  def getBestSplitSuggestions(splitCriterion: SplitCriterion, ht: HoeffdingTreeModel): Array[FeatureSplit] = {
    val bestSplits = new ArrayBuffer[FeatureSplit]()
    featureObservers.zipWithIndex.foreach(x =>
      bestSplits.append(x._1.bestSplit(splitCriterion, classDistribution, x._2, ht.binaryOnly)))
    if (ht.prePrune) {
      bestSplits.append(new FeatureSplit(null, splitCriterion.merit(classDistribution, Array.fill(1)(classDistribution)), new Array[Array[Double]](0)))
    }
    bestSplits.toArray
  }

  override def toString(): String = "level[" + dep + "]ActiveLearningNode:" + weight
}
/**
 * inactive learning node
 */
class InactiveLearningNode(classDistribution: Array[Double])
  extends LearningNode(classDistribution) with Serializable {

  def this(that: InactiveLearningNode) {
    this(Util.mergeArray(that.classDistribution, that.blockClassDistribution))
  }

  /*
   * lean and update the node
   */
  override def learn(ht: HoeffdingTreeModel, example: Example): Unit = {}

  /*
   * whether a learning node is active
   */
  override def isActive(): Boolean = false

  /*
   * merge the two node
   * 
   * @param node the node which will be merged
   * @param trySplit whether to split the current node
   * @return current node
   */
  override def merge(that: Node, trySplit: Boolean): Node = this

  override def toString(): String = "level[" + dep + "] InactiveLearningNode"
}
/**
 * class LearningNodeNB is naive bayes learning node
 */
class LearningNodeNB(classDistribution: Array[Double], featureTypeArray: FeatureTypeArray)
  extends ActiveLearningNode(classDistribution, featureTypeArray) with Serializable {

  def this(that: LearningNodeNB) {
    this(Util.mergeArray(that.classDistribution, that.blockClassDistribution), that.featureTypeArray)
    init()
  }

  /*
   * return the class distribution
   */
  override def classVotes(ht: HoeffdingTreeModel, example: Example): Array[Double] = {
    if (weight() > ht.nbThreshold)
      NaiveBayes.predict(example, classDistribution, featureObservers)
    else super.classVotes(ht, example)
  }
}

/**
 * class LearningNodeNBAdaptive is naive bayes adaptive learning node
 */

class LearningNodeNBAdaptive(classDistribution: Array[Double], featureTypeArray: FeatureTypeArray)
  extends ActiveLearningNode(classDistribution, featureTypeArray) with Serializable {

  var mcCorrectWeight: Double = 0
  var nbCorrectWeight: Double = 0

  var mcBlockCorrectWeight: Double = 0
  var nbBlockCorrectWeight: Double = 0

  def this(that: LearningNodeNBAdaptive) {
    this(Util.mergeArray(that.classDistribution, that.blockClassDistribution), that.featureTypeArray)
    mcCorrectWeight = that.mcCorrectWeight + that.mcBlockCorrectWeight
    nbCorrectWeight = that.nbCorrectWeight + that.nbBlockCorrectWeight
    init()
  }

  /*
   * lean and update the node
   */
  override def learn(ht: HoeffdingTreeModel, example: Example): Unit = {
    super.learn(ht, example)
    if (Util.argmax(classDistribution) == example.labelAt(0)) mcBlockCorrectWeight += example.weight
    if (Util.argmax(NaiveBayes.predict(example, classDistribution, featureObservers)) == example.labelAt(0))
      nbBlockCorrectWeight += example.weight
  }

  /*
   * merge the two node
   * 
   * @param node the node which will be merged
   * @param trySplit whether to split the current node
   * @return current node
   */
  override def merge(that: Node, trySplit: Boolean): Node = {
    if (that.isInstanceOf[LearningNodeNBAdaptive]) {
      val nbaNode = that.asInstanceOf[LearningNodeNBAdaptive]
      //merge weights and class distribution
      if (!trySplit) {
        this.addonWeight += nbaNode.blockClassDistribution.sum
        mcCorrectWeight += nbaNode.mcBlockCorrectWeight
        nbCorrectWeight += nbaNode.nbBlockCorrectWeight
        for (i <- 0 until blockClassDistribution.length)
          this.classDistribution(i) += that.blockClassDistribution(i)
      } else {
        this.addonWeight += nbaNode.addonWeight
        mcCorrectWeight += nbaNode.mcCorrectWeight
        nbCorrectWeight += nbaNode.nbCorrectWeight
        for (i <- 0 until classDistribution.length)
          this.classDistribution(i) += that.classDistribution(i)
      }
      //merge feature class observers
      for (i <- 0 until featureObservers.length)
        featureObservers(i) = featureObservers(i).merge(nbaNode.featureObservers(i), trySplit)

    }
    this
  }
  /*
   * return the class distribution
   */
  override def classVotes(ht: HoeffdingTreeModel, example: Example): Array[Double] = {
    if (mcCorrectWeight > nbCorrectWeight) super.classVotes(ht, example)
    else NaiveBayes.predict(example, classDistribution, featureObservers)
  }
}
