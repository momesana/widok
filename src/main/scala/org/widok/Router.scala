package org.widok

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.RawJSType

case class Route(path: String, page: Page) extends Ordered[Route] {
  val routeParts = path.split('/')

  def compare(that: Route): Int =
    if (routeParts.length != that.routeParts.length)
      routeParts.length.compareTo(that.routeParts.length)
    else routeParts.zip(that.routeParts).foldLeft(0) { (acc, cur) =>
      if (acc != 0) acc
      else cur match {
        case (left, right) =>
          if (left.startsWith(":") && !right.startsWith(":")) -1
          else if (!left.startsWith(":") && right.startsWith(":")) 1
          else left.compareTo(right)
      }
    }

  def apply(args: Map[String, String] = Map.empty) =
    InstantiatedRoute(this, args)

  // Provide a convenience function as most of the time only one argument
  // must be passed.
  def apply(param: String, arg: String) =
    InstantiatedRoute(this, Map(param -> arg))

  def matches(queryParts: Seq[String]) =
    if (routeParts.length != queryParts.length) false
    else routeParts.zip(queryParts).forall({
      case (rt, qry) if rt == qry => true
      case (rt, qry) if rt.startsWith(":") => true
      case _ => false
    })

  def parseArguments(queryParts: Seq[String]) =
    routeParts.zip(queryParts).foldLeft(Map.empty[String, String]) { (acc, cur) =>
      val (key, value) = cur
      if (key.startsWith(":")) acc + (key.substring(1) -> value)
      else acc
    }
}

// TODO Find better name
case class InstantiatedRoute(route: Route, args: Map[String, String] = Map.empty) {
  def query() = {
    val result = args.foldLeft(route.path) { (acc, cur) =>
      val (key, value) = cur
      val replace = s":$key"
      assume(acc.contains(replace), s"Route contains named parameter '$key'")
      acc.replace(replace, value)
    }

    assume(!result.contains(":"), "All parameters were set")
    result
  }

  def uri() = "#" + query()

  /**
   * Dispatches a route by changing the current hash.
   * If the hash did not change, setting it would probably
   * not result in the ``hashchange`` event being triggered.
   * Therefore, render the page manually.
   */
  def go() {
    val current = Router.decode(dom.window.location.hash) // TODO Is the decode() call necessary?
    val target = this.uri()

    if (current == target) {
      log("[router] Hash not changed, re-rendering manually")
      route.page.render(this)
    } else {
      log("[router] Location changed; changing browser hash")
      dom.window.location.hash = target
    }
  }
}

case class Router(unorderedRoutes: Set[Route], startPath: String = "/", fallback: Option[Route] = None) {
  // Checks whether no two elements in ``unorderedRoutes`` are symmetric.
  assume((for {
    x <- unorderedRoutes
    y <- unorderedRoutes.filter(_ != x)
  } yield x.compare(y)).forall(_ != 0), "All routes are distinguishable")

  val routes = unorderedRoutes.toSeq.sorted.reverse

  // TODO See also https://github.com/scala-js/scala-js-dom/issues/53
  @RawJSType
  case class HashChangeEvent(newURL: String, oldURL: String)

  def matchingRoute(queryParts: Seq[String]) =
    routes.find(_.matches(queryParts))

  // Starts listening on the hash change event and triggers routes.
  def listen() {
    dom.window.addEventListener("hashchange", { (e: dom.Event) =>
      val event = e.asInstanceOf[HashChangeEvent]
      dispatchPath(event.newURL,
        if (event.oldURL == "") None
        else Some(event.oldURL))
    }, false)

    if (dom.window.location.hash.isEmpty) {
      dom.window.location.hash = startPath
    } else {
      dispatchPath(dom.window.location.hash)
    }
  }

  /**
   * Only dispatch if the path actually changed.
   * Parses the paths.
   */
  def dispatchPath(nextPath: String, prevPath: Option[String] = None) {
    val nextQuery = Router.parseQuery(nextPath)
    val prevQuery = prevPath.flatMap(Router.parseQuery)

    (prevQuery, nextQuery) match {
      case (None, Some(next)) => dispatchQuery(next)
      case (Some(prev), Some(next)) if prev != next => dispatchQuery(next)
      case _ => log("[router] No action as query did not change")
    }
  }

  def dispatchQuery(query: String) {
    log(s"[router] Dispatching query $query")
    val queryParts = query.split('/')

    matchingRoute(queryParts) match {
      case Some(route) =>
        log(s"[router] Found $route")
        val args = route.parseArguments(queryParts)
        route.page.render(InstantiatedRoute(route, args))

      case _ =>
        error("[router] Choosing fallback route")
        fallback match {
          case Some(fb) => fb.page.render(InstantiatedRoute(fb))
          case None => error("[router] No route found")
        }
    }
  }
}

object Router {
  def decode(query: String) = js.decodeURIComponent(query)

  def parseQuery(uri: String) = Helpers.after(uri, '#').map(decode)
}