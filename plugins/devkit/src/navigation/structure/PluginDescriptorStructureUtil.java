/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.devkit.navigation.structure;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiReference;
import com.intellij.psi.codeStyle.NameUtil;
import com.intellij.psi.util.ProjectIconsAccessor;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.xml.DomElement;
import com.intellij.util.xml.DomManager;
import com.intellij.util.xml.GenericDomValue;
import com.intellij.util.xml.reflect.DomAttributeChildDescription;
import com.intellij.util.xml.reflect.DomFixedChildDescription;
import com.intellij.util.xml.reflect.DomGenericInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.devkit.DevKitBundle;
import org.jetbrains.idea.devkit.dom.Action;
import org.jetbrains.idea.devkit.dom.*;
import org.jetbrains.idea.devkit.dom.impl.ExtensionDomExtender;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UField;
import org.jetbrains.uast.UastContextKt;

import javax.swing.*;
import java.util.List;
import java.util.Set;

public class PluginDescriptorStructureUtil {
  private static final Logger LOG = Logger.getInstance(PluginDescriptorStructureUtil.class);
  private static final Set<String> KNOWN_TOP_LEVEL_NODE_NAMES =
    ContainerUtil.immutableSet("id", "name", "version", "category", "resource-bundle");

  private PluginDescriptorStructureUtil() {
  }


  @NotNull
  public static String getTagDisplayText(@Nullable XmlTag tag) {
    DomElement element = getDomElement(tag);
    if (element == null) {
      return DevKitBundle.message("error.plugin.xml.tag.invalid");
    }

    if (element instanceof Action) {
      String actionId = ((Action)element).getId().getStringValue();
      if (StringUtil.isNotEmpty(actionId)) {
        return actionId;
      }
    }
    else if (element instanceof ExtensionPoint) {
      ExtensionPoint epElement = (ExtensionPoint)element;
      String epName = epElement.getName().getStringValue();
      if (StringUtil.isNotEmpty(epName)) {
        return epName;
      }
      String epQualifiedName = epElement.getQualifiedName().getStringValue();
      if (StringUtil.isNotEmpty(epQualifiedName)) {
        //noinspection ConstantConditions
        return toShortName(epQualifiedName);
      }
    }

    return toDisplayName(element.getXmlElementName()); // default
  }

  @Nullable
  public static Icon getTagIcon(@Nullable XmlTag tag) {
    DomElement element = getDomElement(tag);
    if (element == null) {
      return null;
    }

    if (element instanceof Action) {
      XmlAttributeValue iconAttrValue = ((Action)element).getIcon().getXmlAttributeValue();
      if (iconAttrValue != null) {
        boolean referenceFound = false;
        for (PsiReference reference : iconAttrValue.getReferences()) {
          referenceFound = true;
          Icon icon = getIconFromReference(reference);
          if (icon != null) {
            return icon;
          }
        }

        // icon field initializer may not be available if there're no attached sources for containing class
        if (referenceFound) {
          String value = iconAttrValue.getValue();
          if (value != null) {
            Icon icon = IconLoader.findIcon(value, false);
            if (icon != null) {
              return icon;
            }
          }
        }
      }
    }
    else if (element instanceof Group) {
      return AllIcons.Actions.GroupByPackage;
    }

    return AllIcons.Nodes.Tag;
  }

  @Nullable
  public static String getTagLocationString(@Nullable XmlTag tag) {
    DomElement element = getDomElement(tag);
    if (element == null) {
      return null;
    }

    if (element instanceof IdeaVersion) {
      return getIdeaVersionLocation((IdeaVersion)element);
    }
    if (element instanceof Extensions) {
      return getExtensionsLocation((Extensions)element);
    }
    if (element instanceof ExtensionPoints) {
      return getExtensionPointsLocation((ExtensionPoints)element);
    }
    if (element instanceof ExtensionPoint) {
      return getExtensionPointLocation((ExtensionPoint)element);
    }
    if (element instanceof With) {
      return getWithLocation((With)element);
    }
    if (element instanceof Component) {
      return getComponentLocation((Component)element);
    }
    if (element instanceof Group) {
      return getGroupLocation((Group)element);
    }
    if (element instanceof AddToGroup) {
      return getAddToGroupLocation((AddToGroup)element);
    }
    if (element instanceof Extension) {
      return getExtensionLocation((Extension)element);
    }
    if (element instanceof KeyboardShortcut) {
      return getKeyboardShortcutLocation((KeyboardShortcut)element);
    }
    if (element instanceof Vendor) {
      return getVendorLocation((Vendor)element);
    }
    if (element.getParent() instanceof IdeaPlugin && element instanceof GenericDomValue) {
      return getTopLevelNodeLocation((GenericDomValue)element);
    }

    return guessTagLocation(element);
  }


  @Nullable
  private static String getIdeaVersionLocation(IdeaVersion element) {
    String since = element.getSinceBuild().getStringValue();
    if (StringUtil.isNotEmpty(since)) {
      String until = element.getUntilBuild().getStringValue();
      return since + "-" + (StringUtil.isNotEmpty(until) ? until : "...");
    }
    return null;
  }

  @Nullable
  private static String getExtensionsLocation(Extensions element) {
    return element.getDefaultExtensionNs().getStringValue();
  }

  @Nullable
  private static String getExtensionPointsLocation(ExtensionPoints element) {
    DomElement parent = element.getParent();
    if (parent instanceof IdeaPlugin) {
      return ((IdeaPlugin)parent).getPluginId();
    }
    return null;
  }

  @Nullable
  private static String getExtensionPointLocation(ExtensionPoint element) {
    String epInterface = element.getInterface().getStringValue();
    if (StringUtil.isNotEmpty(epInterface)) {
      return toShortName(epInterface);
    }
    String epBeanClass = element.getBeanClass().getStringValue();
    if (StringUtil.isNotEmpty(epBeanClass)) {
      return toShortName(epBeanClass);
    }
    return null;
  }

  @Nullable
  private static String getWithLocation(With element) {
    return element.getAttribute().getStringValue();
  }

  @Nullable
  private static String getComponentLocation(Component element) {
    String implementationClassText = element.getImplementationClass().getRawText();
    return toShortName(implementationClassText);
  }

  @Nullable
  private static String getGroupLocation(Group element) {
    return element.getId().getStringValue();
  }

  @Nullable
  private static String getAddToGroupLocation(AddToGroup element) {
    return element.getGroupId().getStringValue();
  }

  @Nullable
  private static String getExtensionLocation(Extension element) {
    DomElement parent = element.getParent();
    if (parent instanceof Extensions && Extensions.DEFAULT_PREFIX.equals(((Extensions)parent).getDefaultExtensionNs().getStringValue())) {
      String elementName = element.getXmlElementName();
      if (elementName.equalsIgnoreCase("application-service") || elementName.equalsIgnoreCase("project-service") ||
          elementName.equalsIgnoreCase("module-service")) {
        String result = element.getId().getStringValue();
        if (StringUtil.isEmpty(result)) {
          result = toShortName(firstNotNullAttribute(element, "serviceInterface", "serviceImplementation"));
        }
        return result;
      }
      else if (elementName.equalsIgnoreCase("intentionAction")) {
        return toShortName(getSubTagText(element, "className"));
      }
      else if (elementName.equalsIgnoreCase("dom.extender")) {
        String result = element.getId().getStringValue();
        if (StringUtil.isEmpty(result)) {
          result = toShortName(firstNotNullAttribute(element, "extenderClass"));
        }
        return result;
      }
      else if (elementName.equalsIgnoreCase("stacktrace.fold")) {
        return firstNotNullAttribute(element, "substring");
      }
    }

    return guessTagLocation(element);
  }

  @Nullable
  private static String getKeyboardShortcutLocation(KeyboardShortcut element) {
    return element.getFirstKeystroke().getStringValue();
  }

  @NotNull
  private static String getVendorLocation(Vendor element) {
    return element.getValue();
  }

  @Nullable
  private static String getTopLevelNodeLocation(GenericDomValue element) {
    if (element instanceof Dependency) {
      Dependency dependency = (Dependency)element;
      String result = dependency.getRawText();

      Boolean optional = dependency.getOptional().getValue();
      if (optional != null && optional) {
        result += " [optional]";
      }
      return result;
    }

    if (KNOWN_TOP_LEVEL_NODE_NAMES.contains(element.getXmlElementName().toLowerCase())) {
      return element.getRawText();
    }

    return null;
  }

  @Nullable
  private static String guessTagLocation(DomElement element) {
    String location = null;

    // avoid displaying location equal to display text for actions
    if (!(element instanceof Action)) {
      location = firstNotNullAttribute(element, "id", "name", "displayName", "shortName");
    }
    if (location == null) {
      location = toShortName(firstNotNullAttribute(
        element, "instance", "class", "implementation", "implementationClass", "interface", "interfaceClass"));
    }
    if (location == null) {
      location = firstNotNullAttribute(element, "file");
    }

    if (location != null) {
      return location;
    }

    List<? extends DomAttributeChildDescription> descriptions = element.getGenericInfo().getAttributeChildrenDescriptions();
    String possibleOnlyValue = null;
    for (DomAttributeChildDescription description : descriptions) {
      String value = description.getDomAttributeValue(element).getStringValue();
      if (StringUtil.isEmpty(value)) {
        continue;
      }
      if (possibleOnlyValue == null) {
        possibleOnlyValue = value;
      }
      else {
        // more than one attribute
        possibleOnlyValue = null;
        break;
      }
    }

    if (possibleOnlyValue != null && StringUtil.countChars(possibleOnlyValue, '.') > 2) {
      possibleOnlyValue = toShortName(possibleOnlyValue);
    }

    return possibleOnlyValue;
  }


  @Nullable
  private static String toShortName(@Nullable String fqName) {
    if (fqName == null || fqName.contains(" ")) {
      return null;
    }
    String shortName = StringUtil.substringAfterLast(fqName, ".");
    if (shortName != null) {
      return shortName;
    }
    return fqName;
  }

  @NotNull
  private static String toDisplayName(@NotNull String tagName) {
    String result = tagName.replaceAll("-", " ").replaceAll("\\.", " | ");
    result = StringUtil.join(NameUtil.nameToWords(result), " ");
    result = StringUtil.capitalizeWords(result, true);
    result = StringUtil.replace(
      result,
      new String[] {"Psi", "Dom", "Sdk", "Junit", "Idea", "Javaee", "Jsf", "Mvc", "El", "Id", "Jsp", "Xml"},
      new String[] {"PSI", "DOM", "SDK", "JUnit", "IDEA", "JavaEE", "JSF", "MVC", "EL", "ID", "JSP", "XML"});

    return result;
  }

  @Nullable
  private static String firstNotNullAttribute(DomElement element, String... attributes) {
    DomGenericInfo genericInfo = element.getGenericInfo();
    for (String attribute : attributes) {
      DomAttributeChildDescription description = genericInfo.getAttributeChildDescription(attribute);
      if (description == null) {
        continue;
      }

      String value = description.getDomAttributeValue(element).getStringValue();
      if (StringUtil.isNotEmpty(value)) {
        return value;
      }
    }

    return null;
  }

  @Nullable
  private static String getSubTagText(DomElement element, @SuppressWarnings("SameParameterValue") String subTagName) {
    DomFixedChildDescription subTagDescription = element.getGenericInfo().getFixedChildDescription(subTagName);
    if (subTagDescription == null) {
      return null;
    }
    return subTagDescription.getValues(element).stream()
      .filter(e -> e instanceof ExtensionDomExtender.SimpleTagValue)
      .map(e -> (ExtensionDomExtender.SimpleTagValue)e)
      .map(ExtensionDomExtender.SimpleTagValue::getTagValue)
      .findAny()
      .orElse(null);
  }

  @Nullable
  private static DomElement getDomElement(@Nullable XmlTag tag) {
    if (tag == null) {
      return null;
    }
    Project project = tag.getProject();
    return DomManager.getDomManager(project).getDomElement(tag);
  }

  @Nullable
  private static Icon getIconFromReference(@NotNull PsiReference reference) {
    PsiElement resolved = reference.resolve();
    if (!(resolved instanceof PsiField)) {
      return null;
    }
    UField field = UastContextKt.toUElement(resolved, UField.class);
    if (field == null) {
      LOG.error("Cannot convert PsiField to UField: " + resolved);
      return null;
    }

    UExpression expression = field.getUastInitializer();
    if (expression == null) {
      return null;
    }

    ProjectIconsAccessor iconsAccessor = ProjectIconsAccessor.getInstance(resolved.getProject());
    VirtualFile iconFile = iconsAccessor.resolveIconFile(expression.getPsi());
    return iconFile == null ? null : iconsAccessor.getIcon(iconFile);
  }
}
