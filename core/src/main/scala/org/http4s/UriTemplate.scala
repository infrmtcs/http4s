package org.http4s

import org.http4s.Query.KV
import org.http4s.Uri.{Authority, Host, IPv4, IPv6, RegName, Scheme}
import org.http4s.UriTemplate._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

/**
 * Simple representation of a URI Template that can be rendered as RFC6570
 * conform string.
 *
 * This model reflects only a subset of RFC6570.
 *
 * Level 1 and Level 2 are completely modeled and
 * Level 3 features are limited to:
 *  - Path segments, slash-prefixed
 *  - Form-style query, ampersand-separated
 *  - Fragment expansion
 */
case class UriTemplate(
  scheme: Option[Scheme] = None,
  authority: Option[Authority] = None,
  path: Path = Nil,
  query: Option[UriTemplate.Query] = None,
  fragment: Option[Fragment] = None) {

  /**
   * Replaces any expansion type that matches the given `name`. If no matching
   * `expansion` could be found the same instance will be returned.
   */
  def expandAny[T: QueryParamEncoder](name: String, value: T): UriTemplate =
    expandPath(name, value).expandQuery(name, value).expandFragment(name, value)

  /**
   * Replaces any expansion type in `fragment` that matches the given `name`.
   * If no matching `expansion` could be found the same instance will be
   * returned.
   */
  def expandFragment[T: QueryParamEncoder](name: String, value: T): UriTemplate = {
    if (fragment.isEmpty) this
    else copy(fragment = expandFragmentN(fragment, name, String.valueOf(value)))
  }

  /**
   * Replaces any expansion type in `path` that matches the given `name`. If no
   * matching `expansion` could be found the same instance will be returned.
   */
  def expandPath[T: QueryParamEncoder](name: String, values: List[T]): UriTemplate =
    copy(path = expandPathN(path, name, values.map(QueryParamEncoder[T].encode)))

  /**
   * Replaces any expansion type in `path` that matches the given `name`. If no
   * matching `expansion` could be found the same instance will be returned.
   */
  def expandPath[T: QueryParamEncoder](name: String, value: T): UriTemplate =
    copy(path = expandPathN(path, name, QueryParamEncoder[T].encode(value)::Nil))

  /**
   * Replaces any expansion type in `query` that matches the specified `name`.
   * If no matching `expansion` could be found the same instance will be
   * returned.
   */
  def expandQuery[T: QueryParamEncoder](name: String, values: List[T]): UriTemplate = {
    if (query.isEmpty) this
    else copy(query = expandQueryN(query, name, values.map(String.valueOf(_))))
  }

  /**
   * Replaces any expansion type in `query` that matches the specified `name`.
   * If no matching `expansion` could be found the same instance will be
   * returned.
   */
  def expandQuery(name: String): UriTemplate = expandQuery(name, List[String]())

  /**
   * Replaces any expansion type in `query` that matches the specified `name`.
   * If no matching `expansion` could be found the same instance will be
   * returned.
   */
  def expandQuery[T: QueryParamEncoder](name: String, values: T*): UriTemplate =
    expandQuery(name, values.toList)

  override lazy val toString =
    renderUriTemplate(this)

  /**
   * If no expansion is available an `Uri` will be created otherwise the
   * current instance of `UriTemplate` will be returned.
   */
  def toUriIfPossible: Try[Uri] =
    if (containsExpansions(this)) Failure(new IllegalStateException(s"all expansions must be resolved to be convertable: $this"))
    else Success(toUri(this))

}

object UriTemplate {

  type Path = List[PathDef]
  type Query = List[QueryDef]
  type Fragment = List[FragmentDef]

  protected val unreserved = (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') :+ '-' :+ '.' :+ '_' :+ '~').toSet

  //  protected val genDelims = ':' :: '/' :: '?' :: '#' :: '[' :: ']' :: '@' :: Nil
  //  protected val subDelims = '!' :: '$' :: '&' :: '\'' :: '(' :: ')' :: '*' :: '+' :: ',' :: ';' :: '=' :: Nil
  //  protected val reserved = genDelims ::: subDelims

  def isUnreserved(s: String) = s.forall(unreserved.contains)

  protected def expandPathN(path: Path, name: String, values: List[QueryParameterValue]): Path = {
    val acc = new ArrayBuffer[PathDef]()
    def appendValues() = values foreach { v => acc.append(PathElm(v.value)) }
    path foreach {
      case p@PathElm(_) => acc.append(p)
      case p@VarExp(Seq(n)) =>
        if (n == name) appendValues()
        else acc.append(p)
      case p@VarExp(ns) =>
        if (ns.contains(name)) {
          appendValues()
          acc.append(VarExp(ns.filterNot(_ == name)))
        } else acc.append(p)
      case p@ReservedExp(Seq(n)) =>
        if (n == name) appendValues()
        else acc.append(p)
      case p@ReservedExp(ns) =>
        if (ns.contains(name)) {
          appendValues()
          acc.append(VarExp(ns.filterNot(_ == name)))
        } else acc.append(p)
      case p@PathExp(Seq(n)) =>
        if (n == name) appendValues()
        else acc.append(p)
      case p@PathExp(ns) =>
        if (ns.contains(name)) {
          appendValues()
          acc.append(PathExp(ns.filterNot(_ == name)))
        } else acc.append(p)
    }
    acc.toList
  }

  protected def expandQueryN(query: Option[Query], name: String, values: List[String]): Option[Query] = {
    query.map(f => {
      val acc = new ArrayBuffer[QueryDef]()
      f.foreach {
        case p@ParamElm(_, _) => acc.append(p)
        case p@ParamVarExp(r, List(n)) =>
          if (n == name) acc.append(ParamElm(r, values))
          else acc.append(p)
        case p@ParamVarExp(r, ns) =>
          if (ns.contains(name)) {
            acc.append(ParamElm(r, values))
            acc.append(ParamVarExp(r, ns.filterNot(_ == name)))
          } else acc.append(p)
        case p@ParamReservedExp(r, List(n)) =>
          if (n == name) acc.append(ParamElm(r, values))
          else acc.append(p)
        case p@ParamReservedExp(r, ns) =>
          if (ns.contains(name)) {
            acc.append(ParamElm(r, values))
            acc.append(ParamReservedExp(r, ns.filterNot(_ == name)))
          } else acc.append(p)
        case p@ParamExp(Seq(n)) =>
          if (n == name) acc.append(ParamElm(name, values))
          else acc.append(p)
        case p@ParamExp(ns) =>
          if (ns.contains(name)) {
            acc.append(ParamElm(name, values))
            acc.append(ParamExp(ns.filterNot(_ == name)))
          } else acc.append(p)
        case p@ParamContExp(Seq(n)) =>
          if (n == name) acc.append(ParamElm(name, values))
          else acc.append(p)
        case p@ParamContExp(ns) =>
          if (ns.contains(name)) {
            acc.append(ParamElm(name, values))
            acc.append(ParamContExp(ns.filterNot(_ == name)))
          } else acc.append(p)
      }
      acc.toList
    })
  }

  protected def expandFragmentN(fragment: Option[Fragment], name: String, value: String): Option[Fragment] = {
    fragment.map(f => {
      val acc = new ArrayBuffer[FragmentDef]()
      f.foreach {
        case p@FragmentElm(_) => acc.append(p)
        case p@SimpleFragmentExp(n) => if (n == name) acc.append(FragmentElm(value)) else acc.append(p)
        case p@MultiFragmentExp(Seq(n)) => if (n == name) acc.append(FragmentElm(value)) else acc.append(p)
        case p@MultiFragmentExp(ns) =>
          if (ns.contains(name)) {
            acc.append(FragmentElm(value))
            acc.append(MultiFragmentExp(ns.filterNot(_ == name)))
          } else acc.append(p)
      }
      acc.toList
    })
  }

  protected def renderAuthority(a: Authority): String = a match {
    case Authority(Some(u), h, None) => u + "@" + renderHost(h)
    case Authority(Some(u), h, Some(p)) => u + "@" + renderHost(h) + ":" + p
    case Authority(None, h, Some(p)) => renderHost(h) + ":" + p
    case Authority(_, h, _) => renderHost(h)
    case _ => ""
  }

  protected def renderHost(h: Host): String = h match {
    case RegName(n) => n.toString
    case IPv4(a) => a.toString
    case IPv6(a) => "[" + a.toString + "]"
    case _ => ""
  }

  protected def renderScheme(s: Scheme): String = s + ":"

  protected def renderSchemeAndAuthority(t: UriTemplate): String = t match {
    case UriTemplate(None, None, _, _, _) => ""
    case UriTemplate(Some(s), Some(a), _, _, _) => renderScheme(s) + "//" + renderAuthority(a)
    case UriTemplate(Some(s), None, _, _, _) => renderScheme(s)
    case UriTemplate(None, Some(a), _, _, _) => renderAuthority(a)
  }

  protected def renderQuery(q: Query): String = q match {
    case Nil => "?"
    case ps =>
      val parted = ps partition {
        case ParamElm(_, _) => false
        case ParamVarExp(_, _) => false
        case ParamReservedExp(_, _) => false
        case ParamExp(_) => true
        case ParamContExp(_) => true
      }
      val elements = new ArrayBuffer[String]()
      parted._2 foreach {
        case ParamElm(n, Nil) => elements.append(n)
        case ParamElm(n, List(v)) => elements.append(n + "=" + v)
        case ParamElm(n, vs) => vs.foreach(v => elements.append(n + "=" + v))
        case ParamVarExp(n, vs) => elements.append(n + "=" + "{" + vs.mkString(",") + "}")
        case ParamReservedExp(n, vs) => elements.append(n + "=" + "{+" + vs.mkString(",") + "}")
        case u => throw new IllegalStateException(s"type ${u.getClass.getName} not supported")
      }
      val exps = new ArrayBuffer[String]()
      def separator = if (elements.isEmpty && exps.isEmpty) "?" else "&"
      parted._1 foreach {
        case ParamExp(ns) => exps.append("{" + separator + ns.mkString(",") + "}")
        case ParamContExp(ns) => exps.append("{" + separator + ns.mkString(",") + "}")
        case u => throw new IllegalStateException(s"type ${u.getClass.getName} not supported")
      }
      if (elements.isEmpty) exps.mkString
      else "?" + elements.mkString("&") + exps.mkString
  }

  protected def renderFragment(f: Fragment): String = {
    val elements = new mutable.ArrayBuffer[String]()
    val expansions = new mutable.ArrayBuffer[String]()
    f map {
      case FragmentElm(v) => elements.append(v)
      case SimpleFragmentExp(n) => expansions.append(n)
      case MultiFragmentExp(ns) => expansions.append(ns.mkString(","))
    }
    if (elements.nonEmpty && expansions.nonEmpty)
      "#" + elements.mkString(",") + "{#" + expansions.mkString(",") + "}"
    else if (elements.nonEmpty)
      "#" + elements.mkString(",")
    else if (expansions.nonEmpty)
      "{#" + expansions.mkString(",") + "}"
    else
      "#"
  }

  protected def renderFragmentIdentifier(f: Fragment): String = {
    val elements = new mutable.ArrayBuffer[String]()
    f map {
      case FragmentElm(v) => elements.append(v)
      case SimpleFragmentExp(_) => throw new IllegalStateException("SimpleFragmentExp cannot be converted to a Uri")
      case MultiFragmentExp(_) => throw new IllegalStateException("MultiFragmentExp cannot be converted to a Uri")
    }
    if (elements.isEmpty) ""
    else elements.mkString(",")
  }

  protected def buildQuery(q: Query): org.http4s.Query = {
    val elements = Query.newBuilder
    q map {
      case ParamElm(n, Nil) => elements += KV(n, None)
      case ParamElm(n, List(v)) => elements += KV(n, Some(v))
      case ParamElm(n, vs) => vs.foreach(v => elements += KV(n, Some(v)))
      case u => throw new IllegalStateException(s"${u.getClass.getName} cannot be converted to a Uri")
    }

    elements.result()
  }

  protected def renderPath(p: Path): String = p match {
    case Nil => "/"
    case ps =>
      val elements = new ArrayBuffer[String]()
      ps foreach {
        case PathElm(n) => elements.append("/" + n)
        case VarExp(ns) => elements.append("{" + ns.mkString(",") + "}")
        case ReservedExp(ns) => elements.append("{+" + ns.mkString(",") + "}")
        case PathExp(ns) => elements.append("{/" + ns.mkString(",") + "}")
        case u => throw new IllegalStateException(s"type ${u.getClass.getName} not supported")
      }
      elements.mkString
  }

  protected def renderPathAndQueryAndFragment(t: UriTemplate): String = t match {
    case UriTemplate(_, _, Nil, Some(Seq()), None) => "/?"
    case UriTemplate(_, _, Nil, None, Some(f)) => "/" + renderFragment(f)
    case UriTemplate(_, _, Nil, Some(query), None) => "/" + renderQuery(query)
    case UriTemplate(_, _, Nil, Some(query), Some(f)) => "/" + renderQuery(query) + renderFragment(f)
    case UriTemplate(_, _, path, None, None) => renderPath(path)
    case UriTemplate(_, _, path, Some(query), None) => renderPath(path) + renderQuery(query)
    case UriTemplate(_, _, path, Some(query), Some(f)) => renderPath(path) + renderQuery(query) + renderFragment(f)
    case UriTemplate(_, _, path, None, Some(f)) => renderPath(path) + renderFragment(f)
    case _ => ""
  }

  protected def renderUriTemplate(t: UriTemplate): String = t match {
    case UriTemplate(None, None, Nil, None, None) => "/"
    case UriTemplate(Some(s), Some(a), Nil, None, None) => renderSchemeAndAuthority(t)
    case UriTemplate(Some(s), Some(a), List(), None, None) => renderSchemeAndAuthority(t)
    case UriTemplate(scheme, authority, path, params, fragment) => renderSchemeAndAuthority(t) + renderPathAndQueryAndFragment(t)
    case _ => ""
  }

  protected def fragmentExp(f: FragmentDef): Boolean = f match {
    case FragmentElm(_) => false
    case SimpleFragmentExp(_) => true
    case MultiFragmentExp(_) => true
  }

  protected def pathExp(p: PathDef): Boolean = p match {
    case PathElm(n) => false
    case VarExp(ns) => true
    case ReservedExp(ns) => true
    case PathExp(ns) => true
  }

  protected def queryExp(q: QueryDef): Boolean = q match {
    case ParamElm(_, _) => false
    case ParamVarExp(_, _) => true
    case ParamReservedExp(_, _) => true
    case ParamExp(_) => true
    case ParamContExp(_) => true
  }

  protected def containsExpansions(t: UriTemplate): Boolean = t match {
    case UriTemplate(_, _, Nil, None, None) => false
    case UriTemplate(_, _, Nil, Some(q), Some(f)) => (q exists queryExp) || (f exists fragmentExp)
    case UriTemplate(_, _, Nil, None, Some(f)) => f exists fragmentExp
    case UriTemplate(_, _, Nil, Some(q), None) => q exists queryExp
    case UriTemplate(_, _, p, None, None) => p exists pathExp
    case UriTemplate(_, _, p, Some(q), None) => (p exists pathExp) || (q exists queryExp)
    case UriTemplate(_, _, p, Some(q), Some(f)) => (p exists pathExp) || (q exists queryExp) || (f exists fragmentExp)
    case UriTemplate(_, _, p, None, Some(f)) => (p exists pathExp) || (f exists fragmentExp)
  }

  protected def toUri(t: UriTemplate): Uri = t match {
    case UriTemplate(s, a, Nil, None, None) => Uri(s, a)
    case UriTemplate(s, a, Nil, Some(q), Some(f)) => Uri(s, a, query = buildQuery(q), fragment = Some(renderFragmentIdentifier(f)))
    case UriTemplate(s, a, Nil, None, Some(f)) => Uri(s, a, fragment = Some(renderFragmentIdentifier(f)))
    case UriTemplate(s, a, Nil, Some(q), None) => Uri(s, a, query = buildQuery(q))
    case UriTemplate(s, a, p, None, None) => Uri(s, a, renderPath(p))
    case UriTemplate(s, a, p, Some(q), None) => Uri(s, a, renderPath(p), buildQuery(q))
    case UriTemplate(s, a, p, Some(q), Some(f)) => Uri(s, a, renderPath(p), buildQuery(q), Some(renderFragmentIdentifier(f)))
    case UriTemplate(s, a, p, None, Some(f)) => Uri(s, a, renderPath(p), fragment = Some(renderFragmentIdentifier(f)))
  }

  sealed trait PathDef

  /** Static path element */
  case class PathElm(value: String) extends PathDef

  sealed trait QueryDef

  sealed trait QueryExp extends QueryDef
  /** Static query parameter element */
  case class ParamElm(name: String, values: List[String]) extends QueryDef
  object ParamElm {
    def apply(name: String): ParamElm = new ParamElm(name, Nil)
    def apply(name: String, values: String*): ParamElm = new ParamElm(name, values.toList)
  }

  /**
   * Simple string expansion for query parameter
   */
  case class ParamVarExp(name: String, variables: List[String]) extends QueryDef {
    require(variables forall isUnreserved, "all variables must consist of unreserved characters")
  }
  object ParamVarExp {
    def apply(name: String): ParamVarExp = new ParamVarExp(name, Nil)
    def apply(name: String, variables: String*): ParamVarExp = new ParamVarExp(name, variables.toList)
  }

  /**
   * Reserved string expansion for query parameter
   */
  case class ParamReservedExp(name: String, variables: List[String]) extends QueryDef {
    require(variables forall isUnreserved, "all variables must consist of unreserved characters")
  }
  object ParamReservedExp {
    def apply(name: String): ParamReservedExp = new ParamReservedExp(name, Nil)
    def apply(name: String, variables: String*): ParamReservedExp = new ParamReservedExp(name, variables.toList)
  }

  /**
   * URI Templates are similar to a macro language with a fixed set of macro
   * definitions: the expression type determines the expansion process.
   *
   * The default expression type is simple string expansion (Level 1), wherein a
   * single named variable is replaced by its value as a string after
   * pct-encoding any characters not in the set of unreserved URI characters
   * (<a href="http://tools.ietf.org/html/rfc6570#section-1.5">Section 1.5</a>).
   *
   * Level 2 templates add the plus ("+") operator, for expansion of values that
   * are allowed to include reserved URI characters
   * (<a href="http://tools.ietf.org/html/rfc6570#section-1.5">Section 1.5</a>),
   * and the crosshatch ("#") operator for expansion of fragment identifiers.
   *
   * Level 3 templates allow multiple variables per expression, each
   * separated by a comma, and add more complex operators for dot-prefixed
   * labels, slash-prefixed path segments, semicolon-prefixed path
   * parameters, and the form-style construction of a query syntax
   * consisting of name=value pairs that are separated by an ampersand
   * character.
   */
  sealed trait ExpansionType

  sealed trait FragmentDef

  /** Static fragment element */
  case class FragmentElm(value: String) extends FragmentDef

  /**
   * Fragment expansion, crosshatch-prefixed
   * (<a href="http://tools.ietf.org/html/rfc6570#section-3.2.4">Section 3.2.4</a>)
   */
  case class SimpleFragmentExp(name: String) extends FragmentDef {
    require(name.nonEmpty, "at least one character must be set")
    require(isUnreserved(name), "name must consist of unreserved characters")
  }

  /**
   * Level 1 allows string expansion
   * (<a href="http://tools.ietf.org/html/rfc6570#section-3.2.2">Section 3.2.2</a>)
   *
   * Level 3 allows string expansion with multiple variables
   * (<a href="http://tools.ietf.org/html/rfc6570#section-3.2.2">Section 3.2.2</a>)
   */
  case class VarExp(names: List[String]) extends PathDef {
    require(names.nonEmpty, "at least one name must be set")
    require(names forall isUnreserved, "all names must consist of unreserved characters")
  }
  object VarExp {
    def apply(names: String*): VarExp = new VarExp(names.toList)
  }

  /**
   * Level 2 allows reserved string expansion
   * (<a href="http://tools.ietf.org/html/rfc6570#section-3.2.3">Section 3.2.3</a>)
   *
   * Level 3 allows reserved expansion with multiple variables
   * (<a href="http://tools.ietf.org/html/rfc6570#section-3.2.3">Section 3.2.3</a>)
   */
  case class ReservedExp(names: List[String]) extends PathDef {
    require(names.nonEmpty, "at least one name must be set")
    require(names forall isUnreserved, "all names must consist of unreserved characters")
  }
  object ReservedExp {
    def apply(names: String*): ReservedExp = new ReservedExp(names.toList)
  }

  /**
   * Fragment expansion with multiple variables, crosshatch-prefixed
   * (<a href="http://tools.ietf.org/html/rfc6570#section-3.2.4">Section 3.2.4</a>)
   */
  case class MultiFragmentExp(names: List[String]) extends FragmentDef {
    require(names.nonEmpty, "at least one name must be set")
    require(names forall isUnreserved, "all names must consist of unreserved characters")
  }
  object MultiFragmentExp {
    def apply(names: String*): MultiFragmentExp = new MultiFragmentExp(names.toList)
  }

  /**
   * Path segments, slash-prefixed
   * (<a href="http://tools.ietf.org/html/rfc6570#section-3.2.6">Section 3.2.6</a>)
   */
  case class PathExp(names: List[String]) extends PathDef {
    require(names.nonEmpty, "at least one name must be set")
    require(names forall isUnreserved, "all names must consist of unreserved characters")
  }
  object PathExp {
    def apply(names: String*): PathExp = new PathExp(names.toList)
  }

  /**
   * Form-style query, ampersand-separated
   * (<a href="http://tools.ietf.org/html/rfc6570#section-3.2.8">Section 3.2.8</a>)
   */
  case class ParamExp(names: List[String]) extends QueryExp {
    require(names.nonEmpty, "at least one name must be set")
    require(names forall isUnreserved, "all names must consist of unreserved characters")
  }
  object ParamExp {
    def apply(names: String*): ParamExp = new ParamExp(names.toList)
  }

  /**
   * Form-style query continuation
   * (<a href="http://tools.ietf.org/html/rfc6570#section-3.2.9">Section 3.2.9</a>)
   */
  case class ParamContExp(names: List[String]) extends QueryExp {
    require(names.nonEmpty, "at least one name must be set")
    require(names forall isUnreserved, "all names must consist of unreserved characters")
  }
  object ParamContExp {
    def apply(names: String*): ParamContExp = new ParamContExp(names.toList)
  }

}
