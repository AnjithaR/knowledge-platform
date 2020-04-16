package org.sunbird.actors
import java.util
import java.util.HashMap
import scala.collection.JavaConverters._
import org.sunbird.graph.dac.model.Relation
import javax.inject.Inject
import org.apache.commons.collections4.CollectionUtils
import org.apache.commons.lang3.StringUtils
import org.sunbird.common.{Platform, Slug}
import org.sunbird.common.dto.{Request, Response, ResponseHandler}
import org.sunbird.common.exception.{ClientException, ResponseCode}
import org.sunbird.graph.OntologyEngineContext
import org.sunbird.graph.nodes.DataNode
import org.sunbird.manager.BaseTaxonomyActor
import scala.concurrent.{ExecutionContext, Future}
class FrameworkTermActor @Inject()(implicit oec: OntologyEngineContext) extends BaseTaxonomyActor {
    private val TERM_CREATION_LIMIT = {
        if (Platform.config.hasPath("framework.max_term_creation_limit")) Platform.config.getInt("framework.max_term_creation_limit")
        else 200
    }
    var termList = Future(new util.ArrayList[AnyRef])
    var identifiers: util.List[String] = new util.ArrayList[String]()

    override def onReceive(request: Request): Future[Response] = request.getOperation match {
        case "createTerm" => create(request)
        case _ => ERROR(request.getOperation)
    }

    @SuppressWarnings(Array("unchecked"))
    private def create(request: Request): Future[Response] = {
        val requestList: util.List[util.Map[String, AnyRef]] = getRequestData(request)
        if (null == request.get("term") || null == requestList || requestList.isEmpty)
            throw new ClientException("ERR_INVALID_TERM_OBJECT", "Invalid Request")
        if (TERM_CREATION_LIMIT < requestList.size)
            throw new ClientException("ERR_INVALID_TERM_REQUEST", "No. of request exceeded max limit of " + TERM_CREATION_LIMIT)
        val response = getTermList(requestList, request)
        response.map(resp => {
            if (!ResponseHandler.checkError(resp)) {
                val response = ResponseHandler.OK()
                response.getResult.put("ResponseList", resp)
                response.getResult.put("Identifiers", identifiers)
                response
            }
            else {
                throw new ClientException("ERR_RESOURCE_NOT_FOUND", "Resource not found")
            }
        })
    }

    private def getTermList(requestList: util.List[util.Map[String, AnyRef]], request: Request)(implicit ec : ExecutionContext): Future[Response] = {
        var categoryId: String = request.get("categoryId").asInstanceOf[String]
        val scopeId: String = request.get("frameworkId").asInstanceOf[String]
        if (null != scopeId) {
            categoryId = generateIdentifier(scopeId, categoryId)
            validateRequest(scopeId, categoryId, request)
        }
        else {
            validateCategoryNode(request)
        }
        var codeError: Int = 0
        var serverError: Int = 0
        var id: String = null
        val futures = for (requestMap: util.Map[String, AnyRef] <- requestList.asScala) {
            val req = new Request()
            req.getRequest.putAll(requestMap)
            req.setContext(request.getContext)
            val code: String = req.get("code").asInstanceOf[String]
            if (StringUtils.isNotBlank(code)) {
                if (!requestMap.containsKey("parents") || req.get("parents").asInstanceOf[util.List[AnyRef]].isEmpty) {
                    setRelations(categoryId, req)
                }
                id = generateIdentifier(categoryId, code)
                if (id != null)
                    req.put("identifier", id)
                else {
                    serverError += 1
                    new util.ArrayList[AnyRef]
                }
                req.put("category", request.get("categoryId").asInstanceOf[String])
                val futureList: util.List[Future[Response]] = new util.ArrayList[Future[Response]]
                //                val response = createTerm(req,identifiers)
                createTerm(req, identifiers).map(resp => {
                    val response = resp.asInstanceOf[Future[Response]]
                    futureList.add(response)
                    if (!futureList.isEmpty) {
                        termList = Future.sequence(futureList.asScala).asInstanceOf[Future[util.ArrayList[AnyRef]]]
                        Future(ResponseHandler.OK)
                    }
                    else {
                        serverError += 1
                        Future(ResponseHandler.OK)
                        //                        ResponseHandler.ERROR(ResponseCode.RESOURCE_NOT_FOUND, ResponseCode.RESOURCE_NOT_FOUND.name(), "node cannot be created")
                    }
                })
            }
            else {
                codeError += 1
                Future(ResponseHandler.OK)
                //                ResponseHandler.ERROR(ResponseCode.RESOURCE_NOT_FOUND, ResponseCode.RESOURCE_NOT_FOUND.name(), "code cannot be empty")
            }
        }
        //createResponse(codeError,serverError,identifiers,requestList.size)
        Future.sequence(futures)
    }
    private def generateIdentifier(scopeId: String, code: String) = {
        var id: String = null
        if (StringUtils.isNotBlank(scopeId))
            id = Slug.makeSlug(scopeId + "_" + code)
        id
    }
    private def validateRequest(scopeId: String, categoryId: String, request: Request) = {
        var valid: Boolean = false
        val originalCategory: String = request.get("categoryId").asInstanceOf[String]
        if (StringUtils.isNotBlank(scopeId) && StringUtils.isNotBlank(categoryId) && !StringUtils.equals(categoryId, "_") && !StringUtils.equals(categoryId, scopeId + "_")) {
            request.put("identifier", "categoryId")
            DataNode.read(request).map(resp => {
                if (resp != null) {
                    if (!StringUtils.equals(originalCategory, resp.getMetadata.get("code").asInstanceOf[String]))
                        throw new ClientException("ERR_INVALID_CATEGORY", "Please provide a valid category")
                    if (StringUtils.equalsIgnoreCase(categoryId, resp.getIdentifier)) {
                        val inRelation: List[Relation] = resp.getInRelations.asInstanceOf[List[Relation]]
                        if (!inRelation.isEmpty) {
                            for (relation <- inRelation) {
                                if (StringUtils.equalsIgnoreCase(scopeId, relation.getStartNodeId)) {
                                    valid = true
                                    //add  break
                                }
                            }
                        }
                    }
                }
                else throw new ClientException("ERR_INVALID_CATEGORY_ID", "Please provide valid category.")
                if (!valid) throw new ClientException("ERR_INVALID_CATEGORY_ID", "Please provide valid category framework.")
            })
        }
        else throw new ClientException("ERR_INVALID_CATEGORY_ID", "Please provide required fields. category framework should not be empty.")
    }
    private def validateCategoryNode(request: Request) = {
        val categoryId: String = request.get("categoryId").asInstanceOf[String]
        if (StringUtils.isNotBlank(categoryId)) {
            request.put("identifier", categoryId)
            val response: Response = DataNode.read(request).asInstanceOf[Response]
            if (ResponseHandler.checkError(response))
                throw new ClientException("ERR_INVALID_CATEGORY_ID", "Please provide valid category.")
        }
        else
            throw new ClientException("ERR_INVALID_CATEGORY_ID", "Please provide valid category. It should not be empty.")
    }
    private def setRelations(scopeId: String, request: Request) = {
        request.put("identifier", scopeId)
        DataNode.read(request).map(resp => {
            val objectType: String = resp.getObjectType
            val relationList: util.List[util.Map[String, AnyRef]] = new util.ArrayList[util.Map[String, AnyRef]]()
            val relationMap: util.Map[String, AnyRef] = new HashMap[String, AnyRef]
            relationMap.put("identifier", scopeId)
            relationMap.put("relation", "hasSequenceMember")
            if (StringUtils.isNotBlank(request.get("index").asInstanceOf[String]))
                relationMap.put("index", request.get("index"))
            relationList.add(relationMap)
            objectType.toLowerCase match {
                case "framework" =>
                    request.put("frameworks", relationList)
                case "category" =>
                    request.put("categories", relationList)
                case "categoryinstance" =>
                    request.put("categories", relationList)
                case "channel" =>
                    request.put("channels", relationList)
                case "term" =>
                    request.put("terms", relationList)
            }
        })
    }
    @SuppressWarnings(Array("unchecked"))
    private def getRequestData(request: Request): util.List[util.Map[String, AnyRef]] = {
        if(request.get("term").isInstanceOf[util.List[AnyRef]]){
            request.get("term").asInstanceOf[util.List[util.Map[String, AnyRef]]]
        }
        else
        {
            val requestObj:util.List[util.Map[String, AnyRef]] = new util.ArrayList[util.Map[String, AnyRef]]
            requestObj.add(request.get("term").asInstanceOf[util.Map[String, AnyRef]])
            requestObj
        }
    }
    def createTerm(request: Request, identifiers: util.List[String]):Future[Response]=
    {
        validateTranslations(request)
        DataNode.create(request).map(resp =>
        {
            print("////////////////.....",resp)
            identifiers.add(resp.getIdentifier)
            ResponseHandler.OK()
        })
    }
    @throws[Exception]
    private def createResponse(codeError: Int, serverError: Int, identifiers: util.List[String], size: Int) =
        if (codeError == 0 && serverError == 0) {
            Future(ResponseHandler.OK())
        } else if (codeError > 0 && serverError == 0) {
            if (codeError == size)
                Future(ResponseHandler.ERROR( ResponseCode.CLIENT_ERROR,"ERR_TERM_CODE_REQUIRED", "Unique code is required for Term"))
            else
                Future(ResponseHandler.ERROR(ResponseCode.PARTIAL_SUCCESS,"ERR_TERM_CODE_REQUIRED", "Unique code is required for Term" + "node_id"+ identifiers))
        } else if (codeError == 0 && serverError > 0) {
            if (serverError == size)
                Future(ResponseHandler.ERROR(ResponseCode.SERVER_ERROR, "ERR_SERVER_ERROR", "Internal Server Error"))
            else
                Future(ResponseHandler.ERROR( ResponseCode.PARTIAL_SUCCESS,"ERR_SERVER_ERROR", "Partial Success with Internal Error"  + "node_id" + identifiers))
        } else {
            if ((codeError + serverError) == size) {
                Future(ResponseHandler.ERROR(ResponseCode.SERVER_ERROR, "ERR_SERVER_ERROR", "Internal Server Error and also Invalid Request" ))
            } else
                Future(ResponseHandler.ERROR( ResponseCode.PARTIAL_SUCCESS, "ERR_SERVER_ERROR", "Internal Server Error and also Invalid Request" + "node_id" + identifiers))
        }
}