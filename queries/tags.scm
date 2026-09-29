;;; Processing Lua Mode — tree-sitter tags (go-to-definition)

;; Top-level function definitions
(function_declaration
  name: (identifier) @name.definition.function) @definition.function

;; Local function definitions
(local_function
  name: (identifier) @name.definition.function) @definition.function

;; Variable assignments that bind a function
(assignment_statement
  (variable_list (identifier) @name.definition.var)
  (expression_list (function_definition))) @definition.var

;; Local variable assignments
(local_declaration
  (attribute_list (attribute (identifier) @name.definition.var))) @definition.var

;; Table field that is a function
(field
  name: (identifier) @name.definition.method
  value: (function_definition)) @definition.method
