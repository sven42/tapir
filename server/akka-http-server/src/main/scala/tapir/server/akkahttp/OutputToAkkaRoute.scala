package tapir.server.akkahttp

import java.nio.charset.Charset

import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.{StatusCode => AkkaStatusCode, _}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.StreamConverters
import akka.util.ByteString
import tapir.internal.server.{EncodeOutputBody, EncodeOutputs, OutputValues}
import tapir.model.Part
import tapir.{
  ByteArrayValueType,
  ByteBufferValueType,
  CodecForOptional,
  CodecMeta,
  EndpointOutput,
  FileValueType,
  InputStreamValueType,
  MediaType,
  MultipartValueType,
  RawPart,
  StringValueType
}
import scala.util.Try

private[akkahttp] object OutputToAkkaRoute {
  private type EntityFromLength = Option[Long] => ResponseEntity

  def apply[O](defaultStatusCode: AkkaStatusCode, output: EndpointOutput[O], v: O): Route = {
    val outputValues = encodeOutputs(output, v, OutputValues.empty)

    val statusCode = outputValues.statusCode.map(c => c: AkkaStatusCode).getOrElse(defaultStatusCode)
    val akkaHeaders = parseHeadersOrThrow(outputValues.headers)

    val completeRoute = outputValues.body match {
      case Some(entityFromLength) =>
        val entity = entityFromLength(outputValues.contentLength)
        complete(HttpResponse(entity = overrideContentTypeIfDefined(entity, akkaHeaders), status = statusCode))
      case None => complete(HttpResponse(statusCode))
    }

    if (akkaHeaders.nonEmpty) {
      respondWithHeaders(akkaHeaders)(completeRoute)
    } else {
      completeRoute
    }
  }

  // We can only create the entity once we know if its size is defined; depending on this, the body might end up
  // as a chunked or normal response. That's why here we return a function creating the entity basing on the length,
  // which might be only known when all other outputs are encoded.
  private val encodeOutputs: EncodeOutputs[EntityFromLength] = new EncodeOutputs(new EncodeOutputBody[EntityFromLength] {
    override def rawValueToBody(v: Any, codec: CodecForOptional[_, _ <: MediaType, Any]): EntityFromLength = contentLength =>
      rawValueToResponseEntity(codec.meta, contentLength, v)
    override def streamValueToBody(v: Any, mediaType: MediaType): EntityFromLength = contentLength =>
      streamToEntity(mediaTypeToContentType(mediaType), contentLength, v.asInstanceOf[AkkaStream])
  })

  private def rawValueToResponseEntity[M <: MediaType, R](codecMeta: CodecMeta[_, M, R], contentLength: Option[Long],r: R): ResponseEntity = {
    val ct = mediaTypeToContentType(codecMeta.mediaType)
    codecMeta.rawValueType match {
      case StringValueType(charset) =>
        ct match {
          case nb: ContentType.NonBinary => HttpEntity(nb, r)
          case _                         => HttpEntity(ct, r.getBytes(charset))
        }
      case ByteArrayValueType   => HttpEntity(ct, r)
      case ByteBufferValueType  => HttpEntity(ct, ByteString(r))
      case InputStreamValueType => streamToEntity(ct, contentLength, StreamConverters.fromInputStream(() => r))
      case FileValueType        => HttpEntity.fromPath(ct, r.toPath)
      case mvt: MultipartValueType =>
        val parts = (r: Seq[RawPart]).flatMap(rawPartToBodyPart(mvt, _))
        val body = Multipart.FormData(parts: _*)
        body.toEntity()
    }
  }

  private def streamToEntity(contentType: ContentType, contentLength: Option[Long], stream: AkkaStream): ResponseEntity = {
    contentLength match {
      case None    => HttpEntity(contentType, stream)
      case Some(l) => HttpEntity(contentType, l, stream)
    }
  }

  private def rawPartToBodyPart[T](mvt: MultipartValueType, part: Part[T]): Option[Multipart.FormData.BodyPart] = {
    mvt.partCodecMeta(part.name).map { codecMeta =>
      val headers = part.headers.map {
        case (hk, hv) => parseHeaderOrThrow(hk, hv)
      }

      val partContentLength = part.header("Content-Length").flatMap(v => Try(v.toLong).toOption)
      val body = rawValueToResponseEntity(codecMeta.asInstanceOf[CodecMeta[_, _ <: MediaType, Any]], partContentLength, part.body) match {
        case b: BodyPartEntity => overrideContentTypeIfDefined(b, headers)
        case _                 => throw new IllegalArgumentException(s"${codecMeta.rawValueType} is not supported in multipart bodies")
      }

      Multipart.FormData.BodyPart(part.name, body, part.otherDispositionParams, headers.toList)
    }
  }

  private def mediaTypeToContentType(mediaType: MediaType): ContentType = {
    mediaType match {
      case MediaType.Json()               => ContentTypes.`application/json`
      case MediaType.TextPlain(charset)   => MediaTypes.`text/plain`.withCharset(charsetToHttpCharset(charset))
      case MediaType.OctetStream()        => MediaTypes.`application/octet-stream`
      case MediaType.XWwwFormUrlencoded() => MediaTypes.`application/x-www-form-urlencoded`
      case MediaType.MultipartFormData()  => MediaTypes.`multipart/form-data`
      case mt =>
        ContentType.parse(mt.mediaType).right.getOrElse(throw new IllegalArgumentException(s"Cannot parse content type: $mediaType"))
    }
  }

  private def charsetToHttpCharset(charset: Charset): HttpCharset = HttpCharset.custom(charset.name())

  private def parseHeadersOrThrow(kvs: Vector[(String, String)]): Vector[HttpHeader] = {
    kvs.map { case (k, v) => parseHeaderOrThrow(k, v) }
  }

  private def parseHeaderOrThrow(k: String, v: String): HttpHeader = HttpHeader.parse(k, v) match {
    case ParsingResult.Ok(h, _)     => h
    case ParsingResult.Error(error) => throw new IllegalArgumentException(s"Cannot parse header ($k, $v): $error")
  }

  private def overrideContentTypeIfDefined[RE <: ResponseEntity](re: RE, headers: Seq[HttpHeader]): RE = {
    import akka.http.scaladsl.model.headers.`Content-Type`
    headers
      .collectFirst {
        case `Content-Type`(ct) => ct
      }
      .map(ct => re.withContentType(ct).asInstanceOf[RE])
      .getOrElse(re)
  }
}
