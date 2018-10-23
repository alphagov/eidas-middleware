/*
 * Copyright (c) 2018 Governikus KG. Licensed under the EUPL, Version 1.2 or as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work except
 * in compliance with the Licence. You may obtain a copy of the Licence at:
 * http://joinup.ec.europa.eu/software/page/eupl Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */

package de.governikus.eumw.poseidas.paosservlet.paos.handler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.xml.sax.SAXException;

import de.governikus.eumw.poseidas.ecardcore.utilities.ECardCoreUtil;
import de.governikus.eumw.poseidas.eidmodel.data.EIDKeys;
import de.governikus.eumw.poseidas.eidserver.convenience.EIDSequence;
import de.governikus.eumw.poseidas.eidserver.convenience.session.SessionManager;
import de.governikus.eumw.poseidas.paosservlet.authentication.AuthenticationConstants;
import de.governikus.eumw.poseidas.paosservlet.authentication.paos.Util;
import de.governikus.eumw.poseidas.server.eidservice.EIDInternal;
import de.governikus.eumw.poseidas.server.eidservice.EIDRequestInput;
import de.governikus.eumw.poseidas.server.idprovider.config.PoseidasConfigurator;
import de.governikus.eumw.poseidas.server.idprovider.config.ServiceProviderDto;
import iso.std.iso_iec._24727.tech.schema.ResponseType;
import iso.std.iso_iec._24727.tech.schema.StartPAOS;


abstract public class AbstractPaosHandler
{

  private static final Log LOG = LogFactory.getLog(AbstractPaosHandler.class.getName());

  private static int currentID;

  protected String sessionId;

  protected Object conversationObject;

  protected SOAPMessage soapMessage;

  /**
   * Creates a new PAOS handler for the given servlet request.
   *
   * @param servletRequest the HTTP servlet request containing the PAOS message
   * @param requestBody the request body as byte array <br/>
   *          <b>Do not read the body from request, the stream is already closed!</b>
   * @throws IllegalArgumentException if the message cannot be handled due to special characteristics
   * @throws PaosHandlerException if the message cannot be handled due to wrong PAOS process. (E. g. no
   *           appropriate session exists.)
   * @throws IOException if the request cannot be read
   */
  protected AbstractPaosHandler(HttpServletRequest servletRequest, byte[] requestBody)
    throws PaosHandlerException, IOException
  {
    if (!isPaos(servletRequest))
    {
      throw new IllegalArgumentException("PAOS Conversation stopped: No PAOS received");
    }

    try
    {
      String body = new String(requestBody);
      LOG.debug("Received a PAOS-request from client:\n" + body);
      ByteArrayInputStream bodyStream = new ByteArrayInputStream(body.getBytes());
      conversationObject = Util.unmarshalFirstSoapBodyElement(bodyStream);
    }
    catch (JAXBException | SAXException | ParserConfigurationException | IOException e)
    {
      conversationObject = null;
    }

    try
    {
      soapMessage = Util.unmarshalSOAPMessage(new ByteArrayInputStream(requestBody));
    }
    catch (SOAPException e)
    {
      throw new IllegalArgumentException("Cannot unmarshal soap message", e);
    }

    sessionId = getSessionId();
    // conversationObject == null --> parsing failed, will result in StartPAOSResponse
    if (sessionId == null && conversationObject != null)
    {
      throw new IllegalArgumentException("PAOS Conversation stopped: Cannot determine session ID");
    }

    if (getSessionManager().getSessionInput(sessionId) == null)
    {
      if ("true".equalsIgnoreCase(System.getProperty("attributeprovider.enabled")))
      {
        EIDRequestInput requestInput = new EIDRequestInput(false, false);
        requestInput.setRequestId(this.sessionId);
        requestInput.addRequiredFields(EIDKeys.PROVIDE_SPECIFIC_ATTRIBUTES);
        requestInput.addRequiredFields(EIDKeys.PROVIDE_GLOBAL_GENERIC_ATTRIBUTES);

        // attribute provider must have exactly one service provider
        ServiceProviderDto client = PoseidasConfigurator.getInstance()
                                                      .getCurrentConfig()
                                                      .getServiceProvider()
                                                      .values()
                                                      .iterator()
                                                      .next();
        EIDInternal.getInstance().useID(requestInput, client);
      }
      // conversationObject == null --> parsing failed, will result in StartPAOSResponse
      else if (conversationObject != null)
      {
        throw new PaosHandlerException("Cannot find session for ID : " + sessionId, 403);
      }
    }
  }

  /**
   * Determine the session id.
   */
  protected abstract String getSessionId();

  /**
   * Return true if the received request contains headers indicating a PAOS request.
   *
   * @param servletRequest to be checked
   * @return true if PAOS seems to be used
   */
  private boolean isPaos(HttpServletRequest servletRequest)
  {
    // Check header to be accepted
    String acceptHeader = servletRequest.getHeader(AuthenticationConstants.ACCEPT_HEADER_NAME);
    if (acceptHeader == null)
    {
      acceptHeader = servletRequest.getHeader("Accept");
    }
    if (acceptHeader == null || acceptHeader.indexOf(AuthenticationConstants.PAOS_MEDIA_TYPE) == -1)
    {
      return false;
    }
    String paosHeader = servletRequest.getHeader(AuthenticationConstants.PAOS_VERSION_HEADER_NAME);
    if (paosHeader == null)
    {
      return false;
    }

    // Only require one property out of 3
    if (paosHeader.indexOf("ver=\"" + AuthenticationConstants.PAOS_1_1_URN + "\"") != -1)
    {
      return true;
    }
    if (paosHeader.indexOf("ver=\"" + AuthenticationConstants.PAOS_2_0_URN + "\"") != -1)
    {
      return true;
    }
    // Paos from AusweisApp
    if (paosHeader.indexOf("ver=\"" + AuthenticationConstants.PAOS_1_1_URN_BC_QUIRKSMODE + "\"") != -1)
    {
      LOG.debug("Accept Wrong PAOS Header from AusweisApp: " + paosHeader);
      return true;
    }
    if (paosHeader.indexOf("ver=\"" + AuthenticationConstants.PAOS_2_0_URN_BC_QUIRKSMODE + "\"") != -1)
    {
      LOG.debug("Accept Wrong PAOS Header from AusweisApp: " + paosHeader);
      return true;
    }
    return false;
  }

  /**
   * Writes the PAOS message.
   *
   * @param servletResponse the HTTP servlet response to write the PAOS message.
   * @throws IOException
   */
  public void writeResponse(HttpServletResponse servletResponse) throws IOException
  {
    Object nextConversationObject;
    // input not matching schema
    if (conversationObject == null)
    {
      nextConversationObject = EIDSequence.createStartPAOSResponseSchemaValError();
      removeSession();
    }
    else
    {
      nextConversationObject = performConversation(conversationObject);
    }

    String responseBody;
    try
    {
      responseBody = createPAOSMessage(nextConversationObject);
    }
    catch (IOException e)
    {
      LOG.warn("Fail to create PAOS Response", e);
      throw e;
    }
    catch (Exception e)
    {
      LOG.warn("Fail to create PAOS Response", e);
      removeSession();
      return;
    }

    writeResponse(servletResponse, responseBody);
  }

  /**
   * Perform the PAOS conversation. Creates the next conversation object for the given conversation object.
   *
   * @param obj
   * @return
   */
  private Object performConversation(Object obj)
  {
    EIDSequence eidSequence = getSessionManager().getSession(sessionId).getEIDSequence();

    Object nextObj = null;
    if (obj instanceof StartPAOS)
    {
      eidSequence.setStart((StartPAOS)obj);
      nextObj = eidSequence.getNextRequest(null);
    }
    else if (obj instanceof ResponseType)
    {
      nextObj = eidSequence.getNextRequest((ResponseType)obj);
    }

    if (ECardCoreUtil.isStartPAOSResponse(nextObj))
    {
      removeSession();
    }
    return nextObj;
  }

  protected void removeSession()
  {
    getSessionManager().stopSession(sessionId, null);
  }

  /**
   * Returning the next PAOS message.
   *
   * @param object the conversation object
   * @return the PAOS message as string
   * @throws IOException on any IO error, will not be caught
   * @throws Exception any other exception will be caught
   */
  abstract protected String createPAOSMessage(Object object) throws Exception, IOException;

  /**
   * Sets up the HTTP servlet response and writes the body.
   *
   * @param servletResponse the response to write to
   * @param bodyContent the body content to write
   * @throws IOException on any IO erro
   */
  private void writeResponse(HttpServletResponse servletResponse, String bodyContent) throws IOException
  {
    servletResponse.setContentType("application/vnd.paos+xml");

    try (ServletOutputStream writer = servletResponse.getOutputStream())
    {
      byte[] body = bodyContent.getBytes(StandardCharsets.UTF_8);
      LOG.debug("Write to stream following message:\n" + bodyContent);
      // We set the content length to prevent chunked transfer encoding which the OpenLimit AusweisApp does
      // not like
      servletResponse.setContentLength(body.length);
      writer.write(body);
      LOG.debug("Send message to writer done");
    }
  }

  static String generateUniqueID()
  {
    return "Id" + (currentID++);
  }

  protected SessionManager getSessionManager()
  {
    return SessionManager.getInstance();
  }
}
