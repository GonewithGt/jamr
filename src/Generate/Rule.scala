package edu.cmu.lti.nlp.amr.Generate
import edu.cmu.lti.nlp.amr._

import scala.util.matching.Regex
import scala.collection.mutable.{Map, Set, ArrayBuffer}

case class Rule(argRealizations: List[Arg],               // Sorted list
                concept: ConceptInfo,
                prefix: String,
                //left: List[(String, Int, String)],      // left realization (Int is index into args vector)
                //lex: String,                          // lexical content
                //right: List[(String, Int, String)],     // right realization (Int is index into args vector)
                end: String) {
    def args : List[String] = { argRealizations.map(x => x.label) }
    def left[A](list: List[A]) : List[A] = { list.take(concept.position) }  // takes the first n
    def right[A](list: List[A]) : List[A] = { list.drop(concept.position) } // drops the first n

    def argsWithIndices : List[(Arg, Int)] = {
        argRealizations.zipWithIndex.sortBy(x => x._1.label).zipWithIndex.sortBy(x => x._1._2).map(x => (x._1._1, x._2))
    }

    def mkRule(withArgLabel: Boolean /*= true*/) : String = {
        //"(X "+(lhs ::: args.toList.map(x => (x,"["+x+"]"))).sortBy(_._1).map(_._2).mkString(" ")+") ||| "+rhs(verbose)
        lhs+" ||| "+rhs(withArgLabel)
    }

    def rhs(withArgLabel: Boolean) : String = {
        def printArg(list: List[(Arg, Int)]) : String = {
            if (withArgLabel) {
                // output is "left [ARG0] right"
                list.map(x => x._1.toString).mkString(" ")
            } else {
                // output is "left [1] right"
                list.map(x => x._1.ruleStr(x._2)).mkString(" ")
            }
        }

        return (prefix+" "+printArg(left(argsWithIndices))+" "+concept.realization.words+" "+printArg(right(argsWithIndices))+" "+end).replaceAll("^ | $","")
    }

    def lhs : String = {
        return Rule.graphToCFG(lhsToGraph)
    }

    private def lhsToGraph : Node = {
        val root : Node = Graph.parse(concept.realization.graphFrag).root  // TODO: this is slow
        root.children = root.children ::: argRealizations.map(x => (x.label, Node("", None, "<VARIABLE>", List(), List(), List(), None, new ArrayBuffer())))
        return root
    }

    override def toString() : String = {    // serialize the rule into string format that can loaded using Rule.apply(string) 
        return List(concept.toString, argRealizations.map(x => escape(x.serialize,',')).mkString(","), prefix, end).mkString(" ||| ")
    }

    // below is old code
/*    def argStr(arg: (String, Int, String), verbose: Boolean) : String = {
        if (verbose) {
            (arg._1+" ["+args(arg._2)+"] "+arg._3).replaceAll("^ | $","")
        } else {
            (arg._1+" ["+arg._2.toString+"] "+arg._3).replaceAll("^ | $","")
        }
    }

    def lhs : String = {
        // TODO: cache this
        val frag : Node = Graph.parse(concept.realization.graphFrag)  // TODO: this is slow
        if (frag.children.size + args.size == 0) {   // no children
            if (frag.concept.startsWith("\"")) {
                "(S "+frag.concept+")"
            } else {
                "(X "+frag.concept+")"
            }
        } else {
            // Example: (X (X hit-01) (ARG0 ___) (ARG1 ___))
            val list = for (x <- frag.children) yield {
                "("+x._1+" "+(if (x._2.span == node.span || !sameSpan) { mkLhs(x._2) } else { "["+x._1+"]" } +")")
            }
            "(X (X "+frag.concept+") "+list.mkString(" ")+")"
        }
    } */

}

object Rule {
    def apply(string: String) : Rule = {    // inverse of Rule.toString (deserialize)
        val ruleRegex = """(.*) \|\|\| ([^|]*) \|\|\| ([^|]*) \|\|\| ([^\|]*)""".r
        val ruleRegex(conceptInfo, argsStr, prefix, end) = string
        val args : List[Arg] = unEscapeArray(argsStr,',').map(x => Arg(x)).toList
        return Rule(args, ConceptInfo(conceptInfo), prefix, end)
    }

    def extract(node: Node,                 // extract a rule from a node in a graph, if it can be extracted (no overlapping children)
                graph: Graph,
                sentence: Array[String],
                pos: Array[String],
                spans: Map[String, (Int, Int)])     // maps node.id to start and end of the node and all its children
            : Option[Rule] = {
        case class Child(label: String, node: Node, start: Int, end: Int)

        // we shouldn't need these checks (RuleInventory.extractRules will never violate them) but they're here just in case
        if (!spans.contains(node.id) || node.span == None) {
            return None
        }
        val (ruleStart, ruleEnd) = spans(node.id)
        val children : List[Child] = (
            for { (label, child) <- node.children
                  if spans.contains(child.id)           // filter to aligned children
                  if child.span != node.span            // that are in a different fragment
                } yield {
                    val (start, end) = spans(child.id)
                    Child(Label(label), child, start, end)
                } ).sortBy(x => x.end)

        //logger(1, "children = "+children.toString)

        if (children.size > 0 && !(0 until children.size-1).exists(i => children(i).start > children(i+1).end || children(i).start < ruleStart || children(i).end > ruleEnd)) { // if child spans overlap then no rule can be extracted (these last checks shouldn't ever be violated, but are there for the future)
            var outsideLower = ruleStart
            //do { outsideLower -= 1 } while (outsideLower >= 0 && !spanArray(outsideLower))    // spanArray indicates if the word is aligned to a span
            //outsideLower += 1
            var outsideUpper = ruleEnd
            //while (outsideUpper < sent.size && !spanArray(outsideUpper)) {
            //    outsideUpper += 1
            //}

            val span = graph.spans(node.spans(0))
            val args : List[Child] = children.sortBy(x => x.label)
            val lowerChildren : Vector[Child] = children.filter(x => x.end < span.start).sortBy(_.start).toVector
            val upperChildren : Vector[Child] = children.filter(x => x.start > span.end).sortBy(_.start).toVector
            val prefix : String = sentence.slice(outsideLower, ruleStart).mkString(" ")
            val end : String = sentence.slice(ruleEnd, outsideUpper).mkString(" ")
            val mypos : Array[String] = pos.slice(span.start, span.end)

 /********************** TODO ******************
 *
 * Have alignments for edges and use them here
 *
 ***********************************************/

            var left : List[Arg] = (for { i <- 0 until lowerChildren.size-1 } yield {
                val label = lowerChildren(i).label
                Arg("", label, sentence.slice(lowerChildren(i).end, lowerChildren(i+1).start).mkString(" ")) 
            }).toList
            left = left ::: List(Arg("", lowerChildren.last.label, sentence.slice(lowerChildren.last.end, span.start).mkString(" ")))

            var right : List[Arg] = (for { i <- 1 until upperChildren.size } yield {
                val label = upperChildren(i).label
                Arg(sentence.slice(upperChildren(i-1).end, upperChildren(i).start).mkString(" "), label, "")
            }).toList
            right = Arg(sentence.slice(span.end, upperChildren.head.end).mkString(" "), upperChildren.last.label, "") :: right

            val rule = Rule(left ::: right, ConceptInfo(PhraseConceptPair.fromSpan(span, mypos), left.size), prefix, end)

            return Some(rule)
        } else {
            return None
        }
    }

    def abstractRule(rule: Rule) : Rule = {     // turn a lexicalized rule into an abstract (unlexicalized) rule
        val concept = ConceptInfo(PhraseConceptPair("###", rule.concept.realization.graphFrag, rule.concept.realization.fullPos, rule.concept.realization.headPos), rule.concept.position)
        return Rule(rule.argRealizations, concept, rule.prefix, rule.end)
    }

    def conceptStr(node: Node) : String = {
        return node.concept.replaceAll("""\(""", "-LBR-").replaceAll("""\)""", "-RBR-")
    }

    def graphToCFG(node: Node) : String = {
        val concept = conceptStr(node)
        val children = node.children.map(x => (labelStr(x._1), x._2)).sortBy(_._1)
        if (children.size == 0) {
            if (node.concept.startsWith("\"")) {
                "(S "+concept+")"
            } else if (node.concept == "<VARIABLE>") {
                "[X]"
            } else {
                "(X "+concept+")"
            }
        } else {
            // Example: (X (X hit-01) (ARG0 ___) (ARG1 ___))
            assert(node.concept != "<VARIABLE>", "Error: cannot have a variable with children")
            val list : List[String] = for (x <- children) yield {
                "("+x._1+" "+graphToCFG(x._2)+")"
            }
            "(X (X "+concept+") "+list.sorted.mkString(" ")+")"
        }
    }

    // below is old code
/*
    def mkLhs(x: Node, includeArgs: Boolean = false, sameSpan: Boolean = true) : String = {
        // sameSpan indicates we are in the same fragment as our parent (so don't process children that are not in our span)
        val concept = node.conceptStr
        val children = if (includeArgs || !sameSpan) {
            node.children.map(x => (labelStr(x._1), x._2)).sortBy(_._1)
        } else {
            node.children.filter(x => x._2.span == node.span).map(x => (labelStr(x._1), x._2)).sortBy(_._1)
        }
        if (children.size == 0) {
            if (node.concept.startsWith("\"")) {
                "(S "+concept+")"
            } else {
                "(X "+concept+")"
            }
        } else {
            // Example: (X (X hit-01) (ARG0 ___) (ARG1 ___))
            val list : List[String] = for (x <- children) yield {
                "("+x._1+" "+(if (x._2.span == node.span || !sameSpan) { mkLhs(x._2) } else { "["+x._1+"]" } +")")
            }
            "(X (X "+concept+") "+list.sorted.mkString(" ")+")"
        }
    }

    def mkLhsList(node: Node, sameSpan: Boolean = true) : List[(String, String)] = {
        val children = if (sameSpan) {
            node.children.filter(x => x._2.span == node.span)
        } else {
            node.children
        }
        return ("#", "(X " + node.conceptStr + ")") :: children.map(x => { label=labelStr(x._1); (label, "("+label+" "+mkLhs(x._2, includeArgs=false, sameSpan=sameSpan)+")") } )
    } */
}