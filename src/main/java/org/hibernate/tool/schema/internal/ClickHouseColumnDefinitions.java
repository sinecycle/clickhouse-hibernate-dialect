package org.hibernate.tool.schema.internal;

/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */


import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.Size;
import org.hibernate.mapping.CheckConstraint;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Constraint;
import org.hibernate.mapping.Table;
import org.hibernate.mapping.UniqueKey;
import org.hibernate.tool.schema.extract.spi.ColumnInformation;
import org.hibernate.type.descriptor.jdbc.JdbcType;

import java.util.List;

class ClickHouseColumnDefinitions {

    static boolean hasMatchingType(Column column, ColumnInformation columnInformation, Metadata metadata, Dialect dialect) {
        boolean typesMatch = dialect.equivalentTypes( column.getSqlTypeCode(metadata), columnInformation.getTypeCode() )
                || stripArgs( column.getSqlType( metadata ) ).equalsIgnoreCase( columnInformation.getTypeName() );
        if ( typesMatch ) {
            return true;
        }
        else {
            // Try to resolve the JdbcType by type name and check for a match again based on that type code.
            // This is used to handle SqlTypes type codes like TIMESTAMP_UTC etc.
            final JdbcType jdbcType = dialect.resolveSqlTypeDescriptor(
                    columnInformation.getTypeName(),
                    columnInformation.getTypeCode(),
                    columnInformation.getColumnSize(),
                    columnInformation.getDecimalDigits(),
                    metadata.getDatabase().getTypeConfiguration().getJdbcTypeRegistry()
            );
            return dialect.equivalentTypes( column.getSqlTypeCode(metadata), jdbcType.getDefaultSqlTypeCode() );
        }
    }

    static boolean hasMatchingLength(Column column, ColumnInformation columnInformation, Metadata metadata, Dialect dialect) {
        final int actualSize = columnInformation.getColumnSize();
        if ( actualSize == 0 ) {
            return true;
        }
        else {
            final Size size = column.getColumnSize( dialect, metadata );
            final Long requiredLength = size.getLength();
            final Integer requiredPrecision = size.getPrecision();
            return requiredLength != null && requiredLength == actualSize
                    || requiredPrecision != null && requiredPrecision == actualSize
                    || requiredPrecision == null && requiredLength == null;
        }
    }

    static String getFullColumnDeclaration(
            Column column,
            Table table,
            Metadata metadata,
            Dialect dialect,
            SqlStringGenerationContext context) {
        StringBuilder definition = new StringBuilder();
        appendColumn( definition, column, table, metadata, dialect, context );
        return definition.toString();
    }


    static String getColumnDefinition(Column column, Table table, Metadata metadata, Dialect dialect) {
        StringBuilder definition = new StringBuilder();
        appendColumnDefinition( definition, column, table, metadata, dialect );
        appendComment( definition, column, dialect );
        return definition.toString();
    }

    static void appendColumn(
            StringBuilder statement,
            Column column,
            Table table,
            Metadata metadata,
            Dialect dialect,
            SqlStringGenerationContext context) {
        statement.append( column.getQuotedName( dialect ) );
        appendColumnDefinition( statement, column, table, metadata, dialect );
        appendConstraints( statement, column, table, dialect, context );
        appendComment( statement, column, dialect );
    }

    private static void appendConstraints(
            StringBuilder definition,
            Column column,
            Table table,
            Dialect dialect,
            SqlStringGenerationContext context) {
        if ( column.isUnique() && !table.isPrimaryKey( column ) ) {
            final String keyName = Constraint.generateName( "UK_", table, column);
            final UniqueKey uniqueKey = table.getOrCreateUniqueKey( keyName );
            uniqueKey.addColumn( column );
            definition.append( dialect.getUniqueDelegate().getColumnDefinitionUniquenessFragment( column, context ) );
        }

        if ( dialect.supportsColumnCheck() ) {
            // some databases (Maria, SQL Server) don't like multiple 'check' clauses
            final List<CheckConstraint> checkConstraints = column.getCheckConstraints();
            long anonConstraints = checkConstraints.stream().filter(CheckConstraint::isAnonymous).count();
            if ( anonConstraints == 1 ) {
                for ( CheckConstraint constraint : checkConstraints ) {
                    definition.append( constraint.constraintString() );
                }
            }
            else {
                boolean first = true;
                for ( CheckConstraint constraint : checkConstraints ) {
                    if ( constraint.isAnonymous() ) {
                        if ( first ) {
                            definition.append(" check (");
                            first = false;
                        }
                        else {
                            definition.append(" and ");
                        }
                        definition.append( constraint.getConstraintInParens() );
                    }
                }
                if ( !first ) {
                    definition.append(")");
                }
                for ( CheckConstraint constraint : checkConstraints ) {
                    if ( constraint.isNamed() ) {
                        definition.append( constraint.constraintString() );
                    }
                }
            }
        }
    }

    private static void appendComment(StringBuilder definition, Column column, Dialect dialect) {
        final String columnComment = column.getComment();
        if ( columnComment != null ) {
            definition.append( dialect.getColumnComment( columnComment ) );
        }
    }

    private static void appendColumnDefinition(
            StringBuilder definition,
            Column column,
            Table table,
            Metadata metadata,
            Dialect dialect) {
        final String columnType = column.getSqlType(metadata);
        if ( isIdentityColumn(column, table, metadata, dialect) ) {
            // to support dialects that have their own identity data type
            if ( dialect.getIdentityColumnSupport().hasDataTypeInIdentityColumn() ) {
                definition.append( ' ' ).append( columnType );
            }
            final String identityColumnString = dialect.getIdentityColumnSupport()
                    .getIdentityColumnString( column.getSqlTypeCode(metadata) );
            definition.append( ' ' ).append( identityColumnString );
        }
        else {
            if ( column.hasSpecializedTypeDeclaration() ) {
                definition.append( ' ' ).append( column.getSpecializedTypeDeclaration() );
            }
            final String defaultValue = column.getDefaultValue();
            if ( defaultValue != null ) {
                definition.append( " default " ).append( defaultValue );
            }

            final String generatedAs = column.getGeneratedAs();
            if ( generatedAs != null) {
                definition.append( dialect.generatedAs( generatedAs ) );
            }
            // TODO: check for better way to figure out composite columns
            if ( column.isNullable() && !columnType.toLowerCase().contains("array") && !column.hasSpecializedTypeDeclaration()) {
                definition.append(dialect.getNullColumnString(columnType));
            } else{
                if (!column.hasSpecializedTypeDeclaration()) {
                    definition.append( " " + columnType + " " );
                }
            }
        }
    }

    private static boolean isIdentityColumn(Column column, Table table, Metadata metadata, Dialect dialect) {
        // Try to find out the name of the primary key in case the dialect needs it to create an identity
        return isPrimaryKeyIdentity( table, metadata, dialect )
                && column.getQuotedName( dialect ).equals( getPrimaryKeyColumnName( table, dialect ) );
    }

    private static String getPrimaryKeyColumnName(Table table, Dialect dialect) {
        return table.hasPrimaryKey()
                ? table.getPrimaryKey().getColumns().get(0).getQuotedName( dialect )
                : null;
    }

    private static boolean isPrimaryKeyIdentity(Table table, Metadata metadata, Dialect dialect) {
        // TODO: this is the much better form moving forward as we move to metamodel
        //return hasPrimaryKey
        //				&& table.getPrimaryKey().getColumnSpan() == 1
        //				&& table.getPrimaryKey().getColumn( 0 ).isIdentity();
        MetadataImplementor metadataImplementor = (MetadataImplementor) metadata;
        return table.hasPrimaryKey()
                && table.getIdentifierValue() != null
                && table.getIdentifierValue()
                .isIdentityColumn(
                        metadataImplementor.getMetadataBuildingOptions()
                                .getIdentifierGeneratorFactory(),
                        dialect
                );
    }

    private static String stripArgs(String string) {
        int i = string.indexOf('(');
        return i>0 ? string.substring(0,i) : string;
    }
}
