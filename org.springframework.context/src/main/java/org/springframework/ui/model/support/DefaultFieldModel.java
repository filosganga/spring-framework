/*
 * Copyright 2004-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ui.model.support;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.alert.Alert;
import org.springframework.context.alert.Severity;
import org.springframework.context.message.DefaultMessageFactory;
import org.springframework.context.message.MessageBuilder;
import org.springframework.context.message.ResolvableArgument;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.style.StylerUtils;
import org.springframework.ui.format.Formatter;
import org.springframework.ui.model.BindingStatus;
import org.springframework.ui.model.FieldModel;
import org.springframework.ui.model.ValidationStatus;

/**
 * Default FieldModel implementation suitable for use in most environments.
 * @author Keith Donald
 * @since 3.0
 */
public class DefaultFieldModel implements FieldModel {

	private ValueModel valueModel;

	private FieldModelContext context;

	private ValueBuffer buffer;

	private BindingStatus bindingStatus;

	private Object submittedValue;

	private Exception invalidSubmittedValueCause;

	public DefaultFieldModel(ValueModel valueModel, FieldModelContext context) {
		this.valueModel = valueModel;
		this.context = context;
		buffer = new ValueBuffer(valueModel);
		bindingStatus = BindingStatus.CLEAN;
	}

	// implementing FieldModel

	public String getRenderValue() {
		return format(getValue(), context.getFormatter());
	}

	public Object getValue() {
		if (bindingStatus == BindingStatus.DIRTY || bindingStatus == BindingStatus.COMMIT_FAILURE) {
			return buffer.getValue();
		} else {
			return valueModel.getValue();
		}
	}

	public Class<?> getValueType() {
		return valueModel.getValueType();
	}

	public boolean isEditable() {
		return valueModel.isWriteable() && context.getEditableCondition().isTrue();
	}

	public boolean isEnabled() {
		return context.getEnabledCondition().isTrue();
	}

	public boolean isVisible() {
		return context.getVisibleCondition().isTrue();
	}

	@SuppressWarnings("unchecked")
	public void applySubmittedValue(Object submittedValue) {
		assertEditable();
		assertEnabled();
		if (submittedValue instanceof String) {
			try {
				Formatter formatter = context.getFormatter();
				Object parsedValue = formatter != null ? formatter.parse((String) submittedValue, getLocale())
						: submittedValue;
				buffer.setValue(coerseToValueType(parsedValue));
				this.submittedValue = null;
				bindingStatus = BindingStatus.DIRTY;
			} catch (ParseException e) {
				this.submittedValue = submittedValue;
				invalidSubmittedValueCause = e;
				bindingStatus = BindingStatus.INVALID_SUBMITTED_VALUE;
			} catch (ConversionFailedException e) {
				this.submittedValue = submittedValue;
				invalidSubmittedValueCause = e;
				bindingStatus = BindingStatus.INVALID_SUBMITTED_VALUE;
			}
		} else if (submittedValue instanceof String[]) {
			Object parsedValue;
			String[] submittedValues = (String[]) submittedValue;
			if (isMap()) {
				Formatter keyFormatter = context.getKeyFormatter();
				Formatter valFormatter = context.getElementFormatter();
				Map map = new LinkedHashMap(submittedValues.length);
				for (int i = 0; i < submittedValues.length; i++) {
					String entryString = submittedValues[i];
					try {
						String[] entry = entryString.split("=");
						Object parsedKey = keyFormatter != null ? keyFormatter.parse(entry[0], getLocale()) : entry[0];
						Object parsedVal = valFormatter != null ? valFormatter.parse(entry[1], getLocale()) : entry[1];
						map.put(parsedKey, parsedVal);
					} catch (ParseException e) {
						this.submittedValue = submittedValue;
						invalidSubmittedValueCause = e;
						bindingStatus = BindingStatus.INVALID_SUBMITTED_VALUE;
						break;
					}
				}
				parsedValue = map;
			} else {
				List list = new ArrayList(submittedValues.length);
				Formatter elementFormatter = context.getElementFormatter();
				for (int i = 0; i < submittedValues.length; i++) {
					try {
						Object parsedElement = elementFormatter != null ? elementFormatter.parse(submittedValues[i],
								getLocale()) : submittedValues[i];
						list.add(parsedElement);
					} catch (ParseException e) {
						this.submittedValue = submittedValue;
						invalidSubmittedValueCause = e;
						bindingStatus = BindingStatus.INVALID_SUBMITTED_VALUE;
						break;
					}
				}
				parsedValue = list;
			}
			if (bindingStatus != BindingStatus.INVALID_SUBMITTED_VALUE) {
				try {
					buffer.setValue(coerseToValueType(parsedValue));
					this.submittedValue = null;
					bindingStatus = BindingStatus.DIRTY;
				} catch (ConversionFailedException e) {
					this.submittedValue = submittedValue;
					invalidSubmittedValueCause = e;
					bindingStatus = BindingStatus.INVALID_SUBMITTED_VALUE;
				}
			}
		} else {
			try {
				buffer.setValue(coerseToValueType(submittedValue));
				submittedValue = null;
				bindingStatus = BindingStatus.DIRTY;
			} catch (ConversionFailedException e) {
				this.submittedValue = submittedValue;
				invalidSubmittedValueCause = e;
				bindingStatus = BindingStatus.INVALID_SUBMITTED_VALUE;
			}
		}
	}

	public Object getInvalidSubmittedValue() {
		if (bindingStatus != BindingStatus.INVALID_SUBMITTED_VALUE) {
			throw new IllegalStateException("No invalid submitted value applied to this field");
		}
		return submittedValue;
	}

	public BindingStatus getBindingStatus() {
		return bindingStatus;
	}

	public ValidationStatus getValidationStatus() {
		// TODO implementation
		return ValidationStatus.NOT_VALIDATED;
	}

	public Alert getStatusAlert() {
		if (bindingStatus == BindingStatus.INVALID_SUBMITTED_VALUE) {
			return new AbstractAlert() {
				public String getCode() {
					return "typeMismatch";
				}

				public String getMessage() {
					MessageBuilder builder = new MessageBuilder(context.getMessageSource());
					builder.code(getCode());
					if (invalidSubmittedValueCause instanceof ParseException) {
						ParseException e = (ParseException) invalidSubmittedValueCause;
						builder.arg("label", context.getLabel());
						builder.arg("value", submittedValue);
						builder.arg("errorOffset", e.getErrorOffset());
						builder.defaultMessage(new DefaultMessageFactory() {
							public String createDefaultMessage() {
								return "Failed to bind '" + context.getLabel() + "'; the submitted value "
										+ StylerUtils.style(submittedValue)
										+ " has an invalid format and could no be parsed";
							}
						});
					} else {
						final ConversionFailedException e = (ConversionFailedException) invalidSubmittedValueCause;
						builder.arg("label", new ResolvableArgument(context.getLabel()));
						builder.arg("value", submittedValue);
						builder.defaultMessage(new DefaultMessageFactory() {
							public String createDefaultMessage() {
								return "Failed to bind '" + context.getLabel() + "'; the submitted value "
										+ StylerUtils.style(submittedValue) + " has could not be converted to "
										+ e.getTargetType().getName();
							}
						});
					}
					return builder.build();
				}

				public Severity getSeverity() {
					return Severity.ERROR;
				}
			};
		} else if (bindingStatus == BindingStatus.COMMIT_FAILURE) {
			return new AbstractAlert() {
				public String getCode() {
					return "internalError";
				}

				public String getMessage() {
					return "Internal error occurred; message = [" + buffer.getFlushException().getMessage() + "]";
				}

				public Severity getSeverity() {
					return Severity.FATAL;
				}
			};
		} else if (bindingStatus == BindingStatus.COMMITTED) {
			return new AbstractAlert() {
				public String getCode() {
					return "bindSuccess";
				}

				public String getMessage() {
					MessageBuilder builder = new MessageBuilder(context.getMessageSource());
					builder.code(getCode());
					builder.arg("label", context.getLabel());
					builder.arg("value", submittedValue);
					builder.defaultMessage(new DefaultMessageFactory() {
						public String createDefaultMessage() {
							return "Successfully bound submitted value " + StylerUtils.style(submittedValue)
									+ " to field '" + context.getLabel() + "'";
						}
					});
					return builder.build();
				}

				public Severity getSeverity() {
					return Severity.INFO;
				}
			};
		} else {
			return null;
		}
	}

	public void validate() {
		// TODO implementation
	}

	public void commit() {
		assertEditable();
		assertEnabled();
		if (bindingStatus == BindingStatus.DIRTY) {
			buffer.flush();
			if (buffer.flushFailed()) {
				bindingStatus = BindingStatus.COMMIT_FAILURE;
			} else {
				bindingStatus = BindingStatus.COMMITTED;
			}
		} else {
			throw new IllegalStateException("Field is not dirty; nothing to commit");
		}
	}

	public void revert() {
		if (bindingStatus == BindingStatus.INVALID_SUBMITTED_VALUE) {
			submittedValue = null;
			invalidSubmittedValueCause = null;
			bindingStatus = BindingStatus.CLEAN;
		} else if (bindingStatus == BindingStatus.DIRTY || bindingStatus == BindingStatus.COMMIT_FAILURE) {
			buffer.clear();
			bindingStatus = BindingStatus.CLEAN;
		} else {
			throw new IllegalStateException("Field is clean or committed; nothing to revert");
		}
	}

	public FieldModel getNested(String fieldName) {
		return context.getNested(fieldName);
	}

	public boolean isList() {
		return getValueType().isArray() || List.class.isAssignableFrom(getValueType());
	}

	public FieldModel getListElement(int index) {
		return context.getListElement(index);
	}

	public boolean isMap() {
		return Map.class.isAssignableFrom(getValueType());
	}

	public FieldModel getMapValue(Object key) {
		return context.getMapValue((key));
	}

	@SuppressWarnings("unchecked")
	public String formatValue(Object value) {
		Formatter formatter;
		if (Collection.class.isAssignableFrom(getValueType()) || getValueType().isArray() || isMap()) {
			formatter = context.getElementFormatter();
		} else {
			formatter = context.getFormatter();
		}
		return format(value, formatter);
	}

	// internal helpers

	@SuppressWarnings("unchecked")
	private String format(Object value, Formatter formatter) {
		if (formatter != null) {
			Class<?> formattedType = getFormattedObjectType(formatter.getClass());
			value = context.getConversionService().convert(value, formattedType);
			return formatter.format(value, getLocale());
		} else {
			if (value == null) {
				return "";
			}
			if (context.getConversionService().canConvert(value.getClass(), String.class)) {
				return context.getConversionService().convert(value, String.class);
			} else {
				return value.toString();
			}
		}
	}

	private Locale getLocale() {
		return context.getLocale();
	}

	@SuppressWarnings("unchecked")
	private Class getFormattedObjectType(Class formatterClass) {
		Class classToIntrospect = formatterClass;
		while (classToIntrospect != null) {
			Type[] ifcs = classToIntrospect.getGenericInterfaces();
			for (Type ifc : ifcs) {
				if (ifc instanceof ParameterizedType) {
					ParameterizedType paramIfc = (ParameterizedType) ifc;
					Type rawType = paramIfc.getRawType();
					if (Formatter.class.equals(rawType)) {
						Type arg = paramIfc.getActualTypeArguments()[0];
						if (arg instanceof TypeVariable) {
							arg = GenericTypeResolver.resolveTypeVariable((TypeVariable) arg, formatterClass);
						}
						if (arg instanceof Class) {
							return (Class) arg;
						}
					} else if (Formatter.class.isAssignableFrom((Class) rawType)) {
						return getFormattedObjectType((Class) rawType);
					}
				} else if (Formatter.class.isAssignableFrom((Class) ifc)) {
					return getFormattedObjectType((Class) ifc);
				}
			}
			classToIntrospect = classToIntrospect.getSuperclass();
		}
		return null;
	}

	private Object coerseToValueType(Object value) {
		TypeDescriptor targetType = valueModel.getValueTypeDescriptor();
		ConversionService conversionService = context.getConversionService();
		if (value != null && conversionService.canConvert(value.getClass(), targetType)) {
			return conversionService.convert(value, targetType);
		} else {
			return value;
		}
	}

	private void assertEditable() {
		if (!isEditable()) {
			throw new IllegalStateException("Field is not editable");
		}
	}

	private void assertEnabled() {
		if (!isEditable()) {
			throw new IllegalStateException("Field is not enabled");
		}
	}

	static abstract class AbstractAlert implements Alert {
		public String toString() {
			return getCode() + " - " + getMessage();
		}
	}

}
