package org.sunbird.mimetype.mgr.impl

import java.io.File

import org.sunbird.common.exception.ClientException
import org.sunbird.graph.dac.model.Node
import org.sunbird.mimetype.mgr.{BaseMimeTypeManager, MimeTypeManager}

import scala.concurrent.{ExecutionContext, Future}


object YouTubeMimeTypeMgrImpl extends BaseMimeTypeManager with MimeTypeManager {

	override def upload(objectId: String, node: Node, uploadFile: File)(implicit ec: ExecutionContext): Future[Map[String, Any]] = {
		throw new ClientException("UPLOAD_DENIED", UPLOAD_DENIED_ERR_MSG)
	}

	override def upload(objectId: String, node: Node, fileUrl: String)(implicit ec: ExecutionContext): Future[Map[String, Any]] = {
		validateUploadRequest(objectId, node, fileUrl)
		Future{Map[String, AnyRef]("identifier" -> objectId, "artifactUrl" -> fileUrl)}
	}

}