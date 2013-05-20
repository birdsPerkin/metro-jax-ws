/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */


package com.sun.tools.ws.wsdl.parser;

import com.sun.istack.NotNull;
import com.sun.istack.Nullable;
import com.sun.tools.ws.resources.WscompileMessages;
import com.sun.tools.ws.resources.WsdlMessages;
import com.sun.tools.ws.wscompile.ErrorReceiver;
import com.sun.tools.ws.wscompile.WsimportOptions;
import com.sun.tools.ws.wsdl.document.WSDLConstants;
import com.sun.tools.ws.wsdl.document.schema.SchemaConstants;
import com.sun.tools.ws.wsdl.framework.ParseException;
import com.sun.xml.ws.api.wsdl.parser.MetaDataResolver;
import com.sun.xml.ws.api.wsdl.parser.MetadataResolverFactory;
import com.sun.xml.ws.api.wsdl.parser.ServiceDescriptor;
import com.sun.xml.ws.util.DOMUtil;
import com.sun.xml.ws.util.ServiceFinder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Vivek Pandey
 */
public final class MetadataFinder extends DOMForest{

    public boolean isMexMetadata;
    private String rootWSDL;
    private Set<String> rootWsdls = new HashSet<String>();


    public MetadataFinder(InternalizationLogic logic, WsimportOptions options, ErrorReceiver errReceiver) {
        super(logic, options, errReceiver);

    }

    public void parseWSDL(){
        // parse source grammars
        for (InputSource value : options.getWSDLs()) {
            String systemID = value.getSystemId();
            errorReceiver.pollAbort();

            Document dom ;
            Element doc = null;

            try {
            //if there is entity resolver use it
            if (options.entityResolver != null)
                value = options.entityResolver.resolveEntity(null, systemID);
            if (value == null)
                value = new InputSource(systemID);
                dom = parse(value, true);

                doc = dom.getDocumentElement();
                if (doc == null) {
                    continue;
                }
                //if its not a WSDL document, retry with MEX
                if (doc.getNamespaceURI() == null || !doc.getNamespaceURI().equals(WSDLConstants.NS_WSDL) || !doc.getLocalName().equals("definitions")) {
                    throw new SAXParseException(WsdlMessages.INVALID_WSDL(systemID,
                        com.sun.xml.ws.wsdl.parser.WSDLConstants.QNAME_DEFINITIONS, doc.getNodeName(), locatorTable.getStartLocation(doc).getLineNumber()), locatorTable.getStartLocation(doc));
                }
            } catch(FileNotFoundException e){
                errorReceiver.error(WsdlMessages.FILE_NOT_FOUND(systemID), e);
                return;
            } catch (IOException e) {
                doc = getFromMetadataResolver(systemID, e);
            } catch (SAXParseException e) {
                doc = getFromMetadataResolver(systemID, e);
            } catch (SAXException e) {
                doc = getFromMetadataResolver(systemID, e);
            }

            if (doc == null) {
                continue;
            }

            NodeList schemas = doc.getElementsByTagNameNS(SchemaConstants.NS_XSD, "schema");
            for (int i = 0; i < schemas.getLength(); i++) {
                if(!inlinedSchemaElements.contains(schemas.item(i)))
                    inlinedSchemaElements.add((Element) schemas.item(i));
            }
        }
        identifyRootWslds();
    }

    /**
     * Gives the root wsdl document systemId. A root wsdl document is the one which has wsdl:service.
     * @return null if there is no root wsdl
     */
    public @Nullable
    String getRootWSDL(){
        return rootWSDL;
    }

    /**
     * Gives all the WSDL documents.
     */
    public @NotNull
    Set<String> getRootWSDLs(){
        return rootWsdls;
    }


    /**
     * Identifies WSDL documents from the {@link DOMForest}. Also identifies the root wsdl document.
     */
    private void identifyRootWslds(){
        for(String location: rootDocuments){
            Document doc = get(location);
            if(doc!=null){
                Element definition = doc.getDocumentElement();
                if(definition == null || definition.getLocalName() == null || definition.getNamespaceURI() == null)
                    continue;
                if(definition.getNamespaceURI().equals(WSDLConstants.NS_WSDL) && definition.getLocalName().equals("definitions")){
                    rootWsdls.add(location);
                    //set the root wsdl at this point. Root wsdl is one which has wsdl:service in it
                    NodeList nl = definition.getElementsByTagNameNS(WSDLConstants.NS_WSDL, "service");

                    //TODO:what if there are more than one wsdl with wsdl:service element. Probably such cases
                    //are rare and we will take any one of them, this logic should still work
                    if(nl.getLength() > 0)
                        rootWSDL = location;
                }
            }
        }
        //no wsdl with wsdl:service found, throw error
        if(rootWSDL == null){
            StringBuffer strbuf = new StringBuffer();
            for(String str : rootWsdls){
                strbuf.append(str);
                strbuf.append('\n');
            }
            errorReceiver.error(null, WsdlMessages.FAILED_NOSERVICE(strbuf.toString()));
        }
    }

    /*
    * If source and target namespace are also passed in,
    * then if the mex resolver is found and it cannot get
    * the data, wsimport attempts to add ?wsdl to the
    * address and retrieve the data with a normal http get.
    * This behavior should only happen when trying a
    * mex request first.
    */
    private @Nullable Element getFromMetadataResolver(String systemId, Exception ex) {
        //try MEX
        MetaDataResolver resolver;
        ServiceDescriptor serviceDescriptor = null;
        for (MetadataResolverFactory resolverFactory : ServiceFinder.find(MetadataResolverFactory.class)) {
            resolver = resolverFactory.metadataResolver(options.entityResolver);
            try {
                serviceDescriptor = resolver.resolve(new URI(systemId));
                //we got the ServiceDescriptor, now break
                if (serviceDescriptor != null)
                    break;
            } catch (URISyntaxException e) {
                throw new ParseException(e);
            }
        }

        if (serviceDescriptor != null) {
            errorReceiver.warning(new SAXParseException(WsdlMessages.TRY_WITH_MEX(ex.getMessage()), null, ex));
            return parseMetadata(systemId, serviceDescriptor);
        } else {
            errorReceiver.error(null, WsdlMessages.PARSING_UNABLE_TO_GET_METADATA(ex.getMessage(), WscompileMessages.WSIMPORT_NO_WSDL(systemId)), ex);
        }
        return null;
    }

    private Element parseMetadata(@NotNull String systemId, @NotNull ServiceDescriptor serviceDescriptor) {
        List<? extends Source> mexWsdls = serviceDescriptor.getWSDLs();
        List<? extends Source> mexSchemas = serviceDescriptor.getSchemas();
        Document root = null;
        for (Source src : mexWsdls) {
            if (src instanceof DOMSource) {
                Node n = ((DOMSource) src).getNode();
                Document doc;
                if (n.getNodeType() == Node.ELEMENT_NODE && n.getOwnerDocument() == null) {
                    doc = DOMUtil.createDom();
                    doc.importNode(n, true);
                } else {
                    doc = n.getOwnerDocument();
                }

//                Element e = (n.getNodeType() == Node.ELEMENT_NODE)?(Element)n: DOMUtil.getFirstElementChild(n);
                if (root == null) {
                    //check if its main wsdl, then set it to root
                    NodeList nl = doc.getDocumentElement().getElementsByTagNameNS(WSDLConstants.NS_WSDL, "service");
                    if (nl.getLength() > 0) {
                        root = doc;
                        rootWSDL = src.getSystemId();
                    }
                }
                NodeList nl = doc.getDocumentElement().getElementsByTagNameNS(WSDLConstants.NS_WSDL, "import");
                for(int i = 0; i < nl.getLength(); i++){
                    Element imp = (Element) nl.item(i);
                    String loc = imp.getAttribute("location");
                    if (loc != null) {
                        if (!externalReferences.contains(loc))
                            externalReferences.add(loc);
                    }
                }
                if (core.keySet().contains(systemId))
                    core.remove(systemId);
                core.put(src.getSystemId(), doc);
                isMexMetadata = true;
            }

            //TODO:handle SAXSource
            //TODO:handler StreamSource
        }

        for (Source src : mexSchemas) {
            if (src instanceof DOMSource) {
                Node n = ((DOMSource) src).getNode();
                Element e = (n.getNodeType() == Node.ELEMENT_NODE) ? (Element) n : DOMUtil.getFirstElementChild(n);
                inlinedSchemaElements.add(e);
            }
            //TODO:handle SAXSource
            //TODO:handler StreamSource
        }
        return root.getDocumentElement();
    }
}