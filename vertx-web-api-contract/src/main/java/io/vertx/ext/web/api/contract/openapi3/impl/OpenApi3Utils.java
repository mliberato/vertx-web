package io.vertx.ext.web.api.contract.openapi3.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.ObjectMapperFactory;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.api.validation.SpecFeatureNotSupportedException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Francesco Guardiani @slinkydeveloper
 */
public class OpenApi3Utils {

  public static ParseOptions getParseOptions() {
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    options.setResolveCombinators(false);
    options.setResolveFully(true);
    return options;
  }

  public static boolean isParameterArrayType(Parameter parameter) {
    if (parameter.getSchema() != null && parameter.getSchema().getType() != null)
      return parameter.getSchema().getType().equals("array");
    else return false;
  }

  public static boolean isParameterObjectOrAllOfType(Parameter parameter) {
    return isSchemaObjectOrAllOfType(parameter.getSchema());
  }

  public static boolean isSchemaObjectOrAllOfType(Schema schema) {
    return isSchemaObject(schema) || isAllOfSchema(schema);
  }

  public static boolean isSchemaObject(Schema schema) {
    return schema != null && ("object".equals(schema.getType()) || schema.getProperties() != null);
  }

  public static boolean isRequiredParam(Schema schema, String parameterName) {
    return schema != null && schema.getRequired() != null && schema.getRequired().contains(parameterName);
  }

  public static boolean isRequiredParam(Parameter param) {
    return (param != null) ? param.getRequired() : false;
  }

  public static String resolveStyle(Parameter param) {
    if (param.getStyle() != null) return param.getStyle().toString();
    else switch (param.getIn()) {
      case "query":
        return "form";
      case "path":
        return "simple";
      case "header":
        return "simple";
      case "cookie":
        return "form";
      default:
        return null;
    }
  }

  public static boolean isOneOfSchema(Schema schema) {
    if (!(schema instanceof ComposedSchema)) return false;
    ComposedSchema composedSchema = (ComposedSchema) schema;
    return (composedSchema.getOneOf() != null && composedSchema.getOneOf().size() != 0);
  }

  public static boolean isAnyOfSchema(Schema schema) {
    if (!(schema instanceof ComposedSchema)) return false;
    ComposedSchema composedSchema = (ComposedSchema) schema;
    return (composedSchema.getAnyOf() != null && composedSchema.getAnyOf().size() != 0);
  }

  public static boolean isAllOfSchema(Schema schema) {
    if (!(schema instanceof ComposedSchema)) return false;
    ComposedSchema composedSchema = (ComposedSchema) schema;
    return (composedSchema.getAllOf() != null && composedSchema.getAllOf().size() != 0);
  }

  public static boolean resolveAllowEmptyValue(Parameter parameter) {
    if (parameter.getAllowEmptyValue() != null) {
      // As OAS says: This is valid only for query parameters and allows sending a parameter with an empty value. Default value is false. If style is used, and if behavior is n/a (cannot be serialized), the value of allowEmptyValue SHALL be ignored
      if (!"form".equals(resolveStyle(parameter)))
        return false;
      else
        return parameter.getAllowEmptyValue();
    } else return false;
  }

  // Thank you StackOverflow :) https://stackoverflow
  // .com/questions/28332924/case-insensitive-matching-of-a-string-to-a-java-enum :)
  public static <T extends Enum<?>> T searchEnum(Class<T> enumeration, String search) {
    for (T each : enumeration.getEnumConstants()) {
      if (each.name().compareToIgnoreCase(search) == 0) {
        return each;
      }
    }
    return null;
  }

  public static String resolveContentTypeRegex(String listContentTypes) {
    // Check if it's list
    if (listContentTypes.contains(",")) {
      StringBuilder stringBuilder = new StringBuilder();
      String[] contentTypes = listContentTypes.split(",");
      for (String contentType : contentTypes)
        stringBuilder.append(Pattern.quote(contentType.trim()) + "|");
      stringBuilder.deleteCharAt(stringBuilder.length() - 1);
      return stringBuilder.toString();
    } else return Pattern.quote(listContentTypes);
  }

  public static List<Parameter> mergeParameters(List<Parameter> operationParameters, List<Parameter> parentParameters) {
    if (parentParameters == null && operationParameters == null) {
      return new ArrayList<>();
    } else if (operationParameters == null) {
      return new ArrayList<>(parentParameters);
    } else if (parentParameters == null) {
      return new ArrayList<>(operationParameters);
    } else {
      List<Parameter> result = new ArrayList<>(operationParameters);
      List<Parameter> actualParams = new ArrayList<>(operationParameters);
      for (int i = 0; i < parentParameters.size(); i++) {
        for (int j = 0; j < actualParams.size(); j++) {
          Parameter parentParam = parentParameters.get(i);
          Parameter actualParam = actualParams.get(j);
          if (!(parentParam.getIn().equalsIgnoreCase(actualParam.getIn()) && parentParam.getName().equals(actualParam
            .getName())))
            result.add(parentParam);
        }
      }
      return result;
    }
  }

  protected static class ObjectField {
    Schema schema;
    boolean required;

    public ObjectField(Schema schema, String name, Schema superSchema) {
      this.schema = schema;
      this.required = superSchema.getRequired() != null && superSchema.getRequired().contains(name);
    }

    public Schema getSchema() {
      return schema;
    }

    public boolean isRequired() {
      return required;
    }
  }

  /* This function resolve all properties inside an allOf array of schemas */
  public static Map<String, ObjectField> resolveAllOfArrays(List<Schema> allOfSchemas) {
    Map<String, ObjectField> properties = new HashMap<>();
    for (Schema schema : allOfSchemas) {
      if (schema.getType() != null && !schema.getType().equals("object"))
        throw new SpecFeatureNotSupportedException("allOf only allows inner object types");
      for (Map.Entry<String, ? extends Schema> entry : ((Map<String, Schema>) schema.getProperties()).entrySet()) {
        properties.put(entry.getKey(), new OpenApi3Utils.ObjectField(entry.getValue(), entry.getKey(), schema));
      }
    }
    return properties;
  }

  /* This function check if schema is an allOf array or an object and returns a map of properties */
  public static Map<String, ObjectField> solveObjectParameters(Schema schema) {
    if (OpenApi3Utils.isSchemaObjectOrAllOfType(schema)) {
      if (OpenApi3Utils.isAllOfSchema(schema)) {
        // allOf case
        ComposedSchema composedSchema = (ComposedSchema) schema;
        return resolveAllOfArrays(new ArrayList<>(composedSchema.getAllOf()));
      } else {
        // type object case
        Map<String, ObjectField> properties = new HashMap<>();
        for (Map.Entry<String, ? extends Schema> entry : ((Map<String, Schema>) schema.getProperties()).entrySet()) {
          properties.put(entry.getKey(), new OpenApi3Utils.ObjectField(entry.getValue(), entry.getKey(), schema));
        }
        return properties;
      }
    } else return null;
  }

  private final static Pattern COMPONENTS_REFS_MATCHER = Pattern.compile("^\\#\\/components\\/schemas\\/(.+)$");
  private final static String COMPONENTS_REFS_SUBSTITUTION = "\\#\\/definitions\\/$1";

  public static JSONObject schemaToJSONObject(Schema s) {
    try {
      return new JSONObject(ObjectMapperFactory.createJson().writeValueAsString(s));
    } catch (JsonProcessingException e) {
      throw new RuntimeException("WAT");
    }
  }

  public static JSONObject generateJsonSchema(Schema s, OpenAPI oas) {
    JSONObject n = OpenApi3Utils.schemaToJSONObject(s);
    walkAndSolve(n, n, oas);
    return n;
  }

  private static void walkAndSolve(JSONObject n, JSONObject root, OpenAPI oas) {
    if (n.has("$ref")) {
      replaceRef(n, root, oas);
    } else if (n.has("allOf")) {
      Iterator<Object> it = n.getJSONArray("allOf").iterator();
      while (it.hasNext()) {
        walkAndSolve((JSONObject) it.next(), root, oas);
      }
    } else if (n.has("anyOf")) {
      Iterator<Object> it = n.getJSONArray("anyOf").iterator();
      while (it.hasNext()) {
        walkAndSolve((JSONObject) it.next(), root, oas);
      }
    } else if (n.has("allOf")) {
      Iterator<Object> it = n.getJSONArray("allOf").iterator();
      while (it.hasNext()) {
        walkAndSolve((JSONObject) it.next(), root, oas);
      }
    } else if (n.has("properties")) {
      JSONObject properties = n.getJSONObject("properties");
      Iterator<String> it = properties.keys();
      while (it.hasNext()) {
        walkAndSolve(properties.getJSONObject(it.next()), root, oas);
      }
    } else if (n.has("items")) {
      walkAndSolve(n.getJSONObject("items"), root, oas);
    }
  }

  private static void replaceRef(JSONObject n, JSONObject root, OpenAPI oas) {
    /**
     * If a ref is found, the structure of the schema is circular. The oas parser don't solve circular refs.
     * So I bundle the schema:
     * 1. I update the ref field with a #/definitions/schema_name uri
     * 2. If #/definitions/schema_name is empty, I solve it
     */
    String oldRef = n.getString("$ref");
    Matcher m = COMPONENTS_REFS_MATCHER.matcher(oldRef);
    if (m.lookingAt()) {
      String schemaName = m.group(1);
      String newRef = m.replaceAll(COMPONENTS_REFS_SUBSTITUTION);
      n.remove("$ref");
      n.put("$ref", newRef);
      if (!root.has("definitions") || !root.getJSONObject("definitions").has(schemaName)) {
        Schema s = oas.getComponents().getSchemas().get(schemaName);
        JSONObject schema = OpenApi3Utils.schemaToJSONObject(s);
        // We need to search inside for other refs
        if (!root.has("definitions")) {
          JSONObject definitions = new JSONObject();
          definitions.put(schemaName, schema);
          root.put("definitions", definitions);
        } else {
          root.getJSONObject("definitions").put(schemaName, schema);
        }
        walkAndSolve(schema, root, oas);
      }
    } else throw new RuntimeException("Wrong ref! " + oldRef);
  }

  public static Object convertOrgJSONToVertxJSON(Object obj) {
    if (obj == JSONObject.NULL) {
      return null;
    } if (obj instanceof JSONObject) {
      JsonObject result = new JsonObject();
      for (Map.Entry<String, Object> e : ((JSONObject) obj).toMap().entrySet()) {
        result.put(e.getKey(), convertOrgJSONToVertxJSON(e.getValue()));
      }
      return result;
    } else if (obj instanceof JSONArray) {
      return new JsonArray(((JSONArray) obj).toList().stream().map(OpenApi3Utils::convertOrgJSONToVertxJSON).collect(Collectors.toList()));
    } else
      return obj;
  }

  public static Object convertVertxJSONToOrgJSON(Object obj) {
    if (obj == null) {
      return JSONObject.NULL;
    } if (obj instanceof JsonObject) {
      JSONObject result = new JSONObject();
      for (Map.Entry<String, Object> e : ((JsonObject) obj).getMap().entrySet()) {
        result.put(e.getKey(), convertVertxJSONToOrgJSON(e.getValue()));
      }
      return result;
    } else if (obj instanceof JsonArray) {
      return new JSONArray(((JsonArray) obj).getList().stream().map(OpenApi3Utils::convertVertxJSONToOrgJSON).collect(Collectors.toList()));
    } else
      return obj;
  }

}
