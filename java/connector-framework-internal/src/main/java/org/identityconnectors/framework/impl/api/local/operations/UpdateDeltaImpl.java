/*
 * ====================
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2008-2009 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License("CDDL") (the "License").  You may not use this file
 * except in compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://opensource.org/licenses/cddl1.php
 * See the License for the specific language governing permissions and limitations
 * under the License.
 *
 * When distributing the Covered Code, include this CDDL Header Notice in each file
 * and include the License file at http://opensource.org/licenses/cddl1.php.
 * If applicable, add the following below this CDDL Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 * ====================
 * Portions Copyrighted 2010-2014 ForgeRock AS.
 * Portions Copyrighted 2014 Evolveum
 */
package org.identityconnectors.framework.impl.api.local.operations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.identityconnectors.common.Assertions;
import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.api.operations.GetApiOp;
import org.identityconnectors.framework.api.operations.UpdateDeltaApiOp;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.AttributeDelta;
import org.identityconnectors.framework.common.objects.AttributeDeltaBuilder;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.ConnectorObject;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptions;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.OperationalAttributes;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.UpdateAttributeValuesOp;
import org.identityconnectors.framework.spi.operations.UpdateDeltaOp;
import org.identityconnectors.framework.spi.operations.UpdateOp;

/**
 * Handles both version of update this include simple replace and the advance
 * update.
 */
public class UpdateDeltaImpl extends ConnectorAPIOperationRunner implements UpdateDeltaApiOp {

    // Special logger with SPI operation log name. Used for logging operation entry/exit
    private static final Log OP_LOG = Log.getLog(UpdateDeltaOp.class);

    /**
     * Determines which type of update a connector supports and then uses that
     * handler.
     */
    public UpdateDeltaImpl(final ConnectorOperationalContext context, final Connector connector) {
        super(context, connector);
    }

    /**
     * All the operational attributes that can not be added or deleted.
     */
    static final Set<String> OPERATIONAL_ATTRIBUTE_NAMES = new HashSet<String>();

    static {
        OPERATIONAL_ATTRIBUTE_NAMES.addAll(OperationalAttributes.getOperationalAttributeNames());
        OPERATIONAL_ATTRIBUTE_NAMES.add(Name.NAME);
    }

    @Override
    public Set<AttributeDelta> updateDelta(ObjectClass objclass, Uid uid, Set<AttributeDelta> modifications,
            OperationOptions options) {

        // validate all the parameters..
        Assertions.nullCheck(uid, "uid");
        Assertions.nullCheck(objclass, "objectClass");
        if (ObjectClass.ALL.equals(objclass)) {
            throw new UnsupportedOperationException("Operation is not allowed on __ALL__ object class");
        }
        Assertions.nullCheck(modifications, "modifications");
        // check to make sure there's not a uid..
        for (AttributeDelta attrDelta : modifications) {
            if (attrDelta.is(Uid.NAME)) {
                throw new InvalidAttributeValueException("Parameter 'modifications' contains a uid.");
            }
        }
        // check for things only valid during ADD/DELETE
        for (AttributeDelta attrDelta : modifications) {
            Assertions.nullCheck(attrDelta, "attrDelta from modifications");
            // make sure that none of the values are null..

            if (attrDelta.getValuesToAdd() == null && attrDelta.getValuesToRemove() == null
                    && attrDelta.getValuesToReplace() == null) {
                throw new IllegalArgumentException("Lists of added, removed and replaced values can not be 'null'.");
            }
            if (attrDelta.getValuesToReplace() == null) {
                // make sure that if this an delete/add that it doesn't include
                // certain attributes because it doesn't make any sense..
                String name = attrDelta.getName();
                if (OPERATIONAL_ATTRIBUTE_NAMES.contains(name)) {
                    String msg = String.format(OPERATIONAL_ATTRIBUTE_ERR, name);
                    throw new IllegalArgumentException(msg);
                }
            }
        }

        // cast null as empty
        if (options == null) {
            options = new OperationOptionsBuilder().build();
        }

        final ObjectNormalizerFacade normalizer = getNormalizer(objclass);
        //normalize uid attribute
        uid = (Uid) normalizer.normalizeAttribute(uid);
        //normalize all attributesDelta of modification set
        modifications = normalizeSetAttributesDelta(normalizer, modifications);

        Connector conector = getConnector();

        // if connector is supporting UpdateDeltaOp...
        if (conector instanceof UpdateDeltaOp) {
            UpdateDeltaOp deltaOp = (UpdateDeltaOp) conector;

            logDeltaOpEntry("updateDelta", objclass, uid, modifications, options);

            Set<AttributeDelta> attrsDelta;
            try {
                attrsDelta = deltaOp.updateDelta(objclass, uid, modifications, options);
            } catch (RuntimeException e) {
                SpiOperationLoggingUtil.logOpException(OP_LOG, UpdateDeltaOp.class, "updateDelta", e);
                throw e;
            }

            logDeltaOpExit("updateDelta", attrsDelta);

            // return set of side-effect modifications
            return normalizeSetAttributesDelta(normalizer, attrsDelta);
        } else if (conector instanceof UpdateAttributeValuesOp) {
            UpdateOp op = (UpdateOp) conector;
            UpdateAttributeValuesOp valueOp = (UpdateAttributeValuesOp) conector;

            Set<Attribute> valuesToRemove = new HashSet<Attribute>();
            Set<Attribute> valuesToAdd = new HashSet<Attribute>();
            Set<Attribute> valuesToReplace = new HashSet<Attribute>();

            //allocation of attribute's values for addAttributeValues, removeAttributeValues and update
            for (AttributeDelta attrDelta : modifications) {
                if (attrDelta.getValuesToReplace() != null) {
                    valuesToReplace.add(AttributeBuilder.build(attrDelta.getName(), attrDelta.getValuesToReplace()));
                } else {
                    if (attrDelta.getValuesToAdd() != null) {
                        valuesToAdd.add(AttributeBuilder.build(attrDelta.getName(), attrDelta.getValuesToAdd()));
                    }
                    if (attrDelta.getValuesToRemove() != null) {
                        valuesToRemove.add(AttributeBuilder.build(attrDelta.getName(), attrDelta.getValuesToRemove()));
                    }
                }
            }

            Uid newUid = uid;
            if (!valuesToReplace.isEmpty()) {
                try {
                    //execute update for valuesToReplace
                    newUid = op.update(objclass, uid, valuesToReplace, options);
                } catch (RuntimeException e) {
                    SpiOperationLoggingUtil.logOpException(OP_LOG, UpdateOp.class, "update", e);
                    throw e;
                }

                if (newUid == null) {
                    OP_LOG.warn("Return value from update is 'null'.");
                }
            }

            if (!valuesToAdd.isEmpty()) {
                //execute addAttributeValues for valuesToAdd
                newUid = executeUpdateAttributeValues(valueOp, "addAttributeValues", objclass, newUid, valuesToAdd,
                        options, true);

                if (newUid == null) {
                    OP_LOG.warn("Return value from addAttributeValues is 'null'.");
                }
            }

            if (!valuesToRemove.isEmpty()) {
                //execute removeAttributeValues for valuesToRemove
                newUid = executeUpdateAttributeValues(valueOp, "removeAttributeValues", objclass, newUid,
                        valuesToRemove, options, false);

                if (newUid == null) {
                    OP_LOG.warn("Return value from removeAttributeValues is 'null'.");
                }
            }

            Set<AttributeDelta> sideEffectAttributesDelta = new HashSet<AttributeDelta>();
            if (newUid != null && !uid.getUidValue().equals(newUid.getUidValue())) {
                sideEffectAttributesDelta.add(AttributeDeltaBuilder.build(Uid.NAME, newUid.getUidValue()));
            }

            // return set of side-effect modifications
            return sideEffectAttributesDelta;

        } else {

            UpdateOp op = (UpdateOp) conector;

            // check that this connector supports Search..
            if (!(getConnector() instanceof SearchOp)) {
                throw new UnsupportedOperationException("Connector must support: " + SearchOp.class);
            }

            // add attrs to get to operation options, so that the
            // object we fetch has exactly the set of attributes we require
            // (there may be ones that are not in the default set)
            OperationOptionsBuilder builder = new OperationOptionsBuilder(options);
            Set<String> attrNames = new HashSet<String>();
            for (AttributeDelta attributeDelta : modifications) {
                attrNames.add(attributeDelta.getName());
            }
            builder.setAttributesToGet(attrNames);
            options = builder.build();

            // get the connector object from the resource...
            ConnectorObject o = getConnectorObject(objclass, uid, options);
            if (o == null) {
                throw new UnknownUidException(uid, objclass);
            }
            // get actual attributes
            Set<Attribute> attrsFromSearch = o.getAttributes();
            // create set attributes for update operation
            Set<Attribute> attributesForUpdate = new HashSet<Attribute>();
            // create map that can be modified to get the subset of changes
            Map<String, Attribute> attrsFromSearchMap = AttributeUtil.toMap(attrsFromSearch);
            // run through attributesDelta of the current object..
            for (final AttributeDelta attrFromModification : modifications) {
                // get the name of the update attributes
                String name = attrFromModification.getName();
                // verification if attributeDelta contains replace or add and
                // remove values
                if (attrFromModification.getValuesToReplace() != null) {
                    // add new attribute to list attributes for UpdateOp
                    attributesForUpdate.add(AttributeBuilder.build(name, attrFromModification.getValuesToReplace()));
                } else {
                    Attribute attrFromSearch = attrsFromSearchMap.get(name);
                    List<Object> values;
                    final Attribute attrForUpdate;
                    if (attrFromSearch == null && attrFromModification.getValuesToAdd() != null) {
                        // add new values to attribute which not exist on target
                        attrForUpdate = AttributeBuilder.build(name, attrFromModification.getValuesToAdd());
                    } else if (attrFromSearch == null) {
                        continue;
                    } else {
                        values = CollectionUtil.newList(attrFromSearch.getValue());
                        if (attrFromModification.getValuesToAdd() != null) {
                            // add values to existing values of attribute
                            values.addAll(attrFromModification.getValuesToAdd());
                        }
                        if (attrFromModification.getValuesToRemove() != null) {
                            //remove values if exist on target
                            for (Object val : attrFromModification.getValuesToRemove()) {
                                values.remove(val);
                            }
                        }

                        // create attribute with edit values
                        attrForUpdate = AttributeBuilder.build(name, values);
                    }
                    //add attribute to replaceAttributes 
                    attributesForUpdate.add(attrForUpdate);
                }
            }

            logOpEntry("update", objclass, uid, attributesForUpdate, options);

            Uid ret;
            try {
                //execute update for valuesToReplace
                ret = op.update(objclass, uid, attributesForUpdate, options);
            } catch (RuntimeException e) {
                SpiOperationLoggingUtil.logOpException(OP_LOG, UpdateOp.class, "update", e);
                throw e;
            }

            logOpExit("update", ret);
            if (ret == null) {
                return null;
            }

            Set<AttributeDelta> sideEffectAttributesDelta = new HashSet<AttributeDelta>();
            if (!uid.equals(ret)) {
                sideEffectAttributesDelta.add(AttributeDeltaBuilder.build(Uid.NAME, ret.getValue()));
            }

            // return set of side-effect modifications
            return sideEffectAttributesDelta;
        }
    }

    private Uid executeUpdateAttributeValues(UpdateAttributeValuesOp valueOp, String method, ObjectClass objclass,
            Uid uid, Set<Attribute> valuesToUpdate, OperationOptions options, boolean add) {
        logOpEntry(method, objclass, uid, valuesToUpdate, options);
        Uid ret = null;
        try {
            if (!valuesToUpdate.isEmpty() && !add) {
                ret = valueOp.removeAttributeValues(objclass, uid, valuesToUpdate, options);
            } else {
                ret = valueOp.addAttributeValues(objclass, uid, valuesToUpdate, options);
            }
        } catch (RuntimeException e) {
            SpiOperationLoggingUtil.logOpException(OP_LOG, UpdateOp.class, method, e);
            throw e;
        }
        logOpExit(method, ret);
        return ret;
    }

    private Set<AttributeDelta> normalizeSetAttributesDelta(ObjectNormalizerFacade normalizer,
            Set<AttributeDelta> attrsDelta) {
        if (attrsDelta == null) {
            return null;
        }
        Set<AttributeDelta> normalizeModifications = new HashSet<AttributeDelta>();
        List<Object> replaceValues;
        List<Object> addValues = null;
        List<Object> removeValues = null;
        AttributeDelta tempAttrDelta;
        for (AttributeDelta attrDelta : attrsDelta) {
            if (attrDelta.getValuesToReplace() != null) {
                replaceValues = normalizeListAttributesValues(normalizer, attrDelta.getName(),
                        attrDelta.getValuesToReplace());
                tempAttrDelta = AttributeDeltaBuilder.build(attrDelta.getName(), replaceValues);
            } else {
                addValues = null;
                removeValues = null;
                if (attrDelta.getValuesToAdd() != null) {
                    addValues = normalizeListAttributesValues(normalizer, attrDelta.getName(),
                            attrDelta.getValuesToAdd());
                }
                if (attrDelta.getValuesToRemove() != null) {
                    removeValues = normalizeListAttributesValues(normalizer, attrDelta.getName(),
                            attrDelta.getValuesToRemove());
                }
                tempAttrDelta = AttributeDeltaBuilder.build(attrDelta.getName(), addValues, removeValues);
            }
            normalizeModifications.add(tempAttrDelta);
        }
        return Collections.unmodifiableSet(normalizeModifications);
    }

    private List<Object> normalizeListAttributesValues(ObjectNormalizerFacade normalizer, String name,
            List<Object> values) {
        List<Object> resultsValues = new ArrayList<Object>();
        for (Object value : values) {
            Attribute normalizeAttr = normalizer.normalizeAttribute(AttributeBuilder.build(name, value));
            resultsValues.add(normalizeAttr.getValue().get(0));
        }
        return resultsValues;
    }

    /**
     * Get the {@link ConnectorObject} to modify.
     */
    private ConnectorObject getConnectorObject(ObjectClass oclass, Uid uid, OperationOptions options) {
        // attempt to get the connector object..
        GetApiOp get = new GetImpl(new SearchImpl(getOperationalContext(), getConnector()));
        return get.getObject(oclass, uid, options);
    }

    private static final String OPERATIONAL_ATTRIBUTE_ERR =
            "Operational attribute '%s' can not be added or removed.";

    private static void logOpEntry(String opName, ObjectClass objectClass, Uid uid, Set<Attribute> attrs,
            OperationOptions options) {
        if (!isLoggable()) {
            return;
        }
        StringBuilder bld = new StringBuilder();
        bld.append("Enter: ").append(opName).append("(");
        bld.append(objectClass).append(", ");
        bld.append(uid).append(", ");
        bld.append(attrs).append(", ");
        bld.append(options).append(")");
        final String msg = bld.toString();
        OP_LOG.log(UpdateOp.class, opName, SpiOperationLoggingUtil.LOG_LEVEL, msg, null);
    }

    private static void logOpExit(String opName, Uid uid) {
        if (!isLoggable()) {
            return;
        }
        OP_LOG.log(UpdateOp.class, opName, SpiOperationLoggingUtil.LOG_LEVEL, "Return: " + uid, null);
    }

    private static void logDeltaOpEntry(String opName, ObjectClass objectClass, Uid uid, Set<AttributeDelta> attrsDelta,
            OperationOptions options) {
        if (!isLoggable()) {
            return;
        }
        StringBuilder bld = new StringBuilder();
        bld.append("Enter: ").append(opName).append("(");
        bld.append(objectClass).append(", ");
        bld.append(uid).append(", ");
        bld.append(attrsDelta).append(", ");
        bld.append(options).append(")");
        final String msg = bld.toString();
        OP_LOG.log(UpdateDeltaOp.class, opName, SpiOperationLoggingUtil.LOG_LEVEL, msg, null);
    }

    private static void logDeltaOpExit(String opName, Set<AttributeDelta> attrsDelta) {
        if (!isLoggable()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Return: ").append(attrsDelta);
        OP_LOG.log(UpdateDeltaOp.class, opName, SpiOperationLoggingUtil.LOG_LEVEL, sb.toString(), null);
    }

    private static boolean isLoggable() {
        return OP_LOG.isLoggable(SpiOperationLoggingUtil.LOG_LEVEL);
    }
}
