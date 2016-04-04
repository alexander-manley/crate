/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze;

import com.google.common.base.Preconditions;
import io.crate.Constants;
import io.crate.analyze.symbol.Reference;
import io.crate.exceptions.InvalidColumnNameException;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.GeneratedReferenceInfo;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.ReferenceInfo;
import io.crate.sql.tree.Insert;

import java.util.ArrayList;
import java.util.Locale;

public abstract class AbstractInsertAnalyzer {

    protected final AnalysisMetaData analysisMetaData;

    protected AbstractInsertAnalyzer(AnalysisMetaData analysisMetaData) {
        this.analysisMetaData = analysisMetaData;
    }

    private IllegalArgumentException tooManyValuesException(int actual, int expected) {
        throw new IllegalArgumentException(
                String.format(Locale.ENGLISH,
                        "INSERT statement contains a VALUES clause with too many elements (%s), expected (%s)",
                        actual, expected));
    }

    protected void handleInsertColumns(Insert node, int maxInsertValues, AbstractInsertAnalyzedStatement context) {
        // allocate columnsLists
        int numColumns;

        if (node.columns().size() == 0) { // no columns given in statement
            numColumns = context.tableInfo().columns().size();
            if (maxInsertValues > numColumns) {
                throw tooManyValuesException(maxInsertValues, numColumns);
            }
            context.columns(new ArrayList<Reference>(numColumns));

            int i = 0;
            for (ReferenceInfo columnInfo : context.tableInfo().columns()) {
                if (i >= maxInsertValues) {
                    break;
                }
                addColumn(columnInfo.ident().columnIdent().name(), context, i);
                i++;
            }

        } else {
            numColumns = node.columns().size();
            if (maxInsertValues > numColumns) {
                throw tooManyValuesException(maxInsertValues, numColumns);
            }
            context.columns(new ArrayList<Reference>(numColumns));
            for (int i = 0; i < node.columns().size(); i++) {
                addColumn(node.columns().get(i), context, i);
            }
        }

        if (context.primaryKeyColumnIndices().size() == 0 &&
                !(context.tableInfo().primaryKey().isEmpty() || context.tableInfo().hasAutoGeneratedPrimaryKey())) {
            boolean referenced = false;
            for (ColumnIdent columnIdent : context.tableInfo().primaryKey()) {
                if (checkReferencesForGeneratedColumn(columnIdent, context)) {
                    referenced = true;
                    break;
                }
            }
            if (!referenced) {
                throw new IllegalArgumentException("Primary key is required but is missing from the insert statement");
            }
        }
        ColumnIdent clusteredBy = context.tableInfo().clusteredBy();
        if (clusteredBy != null && !clusteredBy.name().equalsIgnoreCase("_id") && context.routingColumnIndex() < 0) {
            if (!checkReferencesForGeneratedColumn(clusteredBy, context)) {
                throw new IllegalArgumentException("Clustered by value is required but is missing from the insert statement");
            }
        }
    }

    private boolean checkReferencesForGeneratedColumn(ColumnIdent columnIdent, AbstractInsertAnalyzedStatement context) {
        ReferenceInfo referenceInfo = context.tableInfo().getReferenceInfo(columnIdent);
        if (referenceInfo instanceof GeneratedReferenceInfo) {
            for (ReferenceInfo referencedReference : ((GeneratedReferenceInfo) referenceInfo).referencedReferenceInfos()) {
                for (Reference column : context.columns()) {
                    if (column.info().equals(referencedReference) ||
                        referencedReference.ident().columnIdent().isChildOf(column.ident().columnIdent())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * validates the column and sets primary key / partitioned by / routing information as well as a
     * column Reference to the context.
     *
     * the created column reference is returned
     */
    protected Reference addColumn(String column, AbstractInsertAnalyzedStatement context, int i) {
        assert context.tableInfo() != null;
        return addColumn(new ReferenceIdent(context.tableInfo().ident(), column), context, i);
    }

    /**
     * validates the column and sets primary key / partitioned by / routing information as well as a
     * column Reference to the context.
     *
     * the created column reference is returned
     */
    protected Reference addColumn(ReferenceIdent ident, AbstractInsertAnalyzedStatement context, int i) {
        final ColumnIdent columnIdent = ident.columnIdent();
        Preconditions.checkArgument(!columnIdent.name().startsWith("_"), "Inserting system columns is not allowed");
        if (Constants.INVALID_COLUMN_NAME_PREDICATE.apply(columnIdent.name())) {
            throw new InvalidColumnNameException(columnIdent.name());
        }

        // set primary key column if found
        for (ColumnIdent pkIdent : context.tableInfo().primaryKey()) {
            if (pkIdent.getRoot().equals(columnIdent)) {
                context.addPrimaryKeyColumnIdx(i);
            }
        }

        // set partitioned column if found
        for (ColumnIdent partitionIdent : context.tableInfo().partitionedBy()) {
            if (partitionIdent.getRoot().equals(columnIdent)) {
                context.addPartitionedByIndex(i);
            }
        }

        // set routing if found
        ColumnIdent routing = context.tableInfo().clusteredBy();
        if (routing != null && (routing.equals(columnIdent) || routing.isChildOf(columnIdent))) {
            context.routingColumnIndex(i);
        }

        // ensure that every column is only listed once
        Reference columnReference = context.allocateUniqueReference(ident);
        context.columns().add(columnReference);
        return columnReference;
    }
}
