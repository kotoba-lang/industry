//! Kotoba UI Components Library
//!
//! This module provides a comprehensive set of UI components
//! that can be used to build beautiful, accessible web interfaces.

use crate::Result;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Core UI component trait
#[async_trait::async_trait]
pub trait UIComponent: Send + Sync {
    /// Render the component to HTML
    async fn render(&self) -> Result<String>;

    /// Get component name for debugging
    fn name(&self) -> &str;

    /// Get component props
    fn props(&self) -> &ComponentProps;
}

/// Base component properties
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ComponentProps {
    pub id: Option<String>,
    pub class: Option<String>,
    pub style: Option<String>,
    pub data_attributes: HashMap<String, String>,
    pub aria_attributes: HashMap<String, String>,
}

impl ComponentProps {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn id(mut self, id: impl Into<String>) -> Self {
        self.id = Some(id.into());
        self
    }

    pub fn class(mut self, class: impl Into<String>) -> Self {
        self.class = Some(class.into());
        self
    }

    pub fn style(mut self, style: impl Into<String>) -> Self {
        self.style = Some(style.into());
        self
    }

    pub fn data(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.data_attributes.insert(key.into(), value.into());
        self
    }

    pub fn aria(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.aria_attributes.insert(key.into(), value.into());
        self
    }

    /// Generate HTML attributes string
    pub fn to_attributes(&self) -> String {
        let mut attrs = Vec::new();

        if let Some(id) = &self.id {
            attrs.push(format!("id=\"{}\"", id));
        }

        if let Some(class) = &self.class {
            attrs.push(format!("class=\"{}\"", class));
        }

        if let Some(style) = &self.style {
            attrs.push(format!("style=\"{}\"", style));
        }

        for (key, value) in &self.data_attributes {
            attrs.push(format!("data-{}=\"{}\"", key, value));
        }

        for (key, value) in &self.aria_attributes {
            attrs.push(format!("aria-{}=\"{}\"", key, value));
        }

        if attrs.is_empty() {
            String::new()
        } else {
            format!(" {}", attrs.join(" "))
        }
    }
}

/// Button component
#[derive(Debug, Clone)]
pub struct Button {
    pub label: String,
    pub variant: ButtonVariant,
    pub size: ButtonSize,
    pub disabled: bool,
    pub props: ComponentProps,
}

#[derive(Debug, Clone)]
pub enum ButtonVariant {
    Primary,
    Secondary,
    Outline,
    Ghost,
    Danger,
}

#[derive(Debug, Clone)]
pub enum ButtonSize {
    Sm,
    Md,
    Lg,
    Xl,
}

impl Button {
    pub fn new(label: impl Into<String>) -> Self {
        Self {
            label: label.into(),
            variant: ButtonVariant::Primary,
            size: ButtonSize::Md,
            disabled: false,
            props: ComponentProps::new(),
        }
    }

    pub fn variant(mut self, variant: ButtonVariant) -> Self {
        self.variant = variant;
        self
    }

    pub fn size(mut self, size: ButtonSize) -> Self {
        self.size = size;
        self
    }

    pub fn disabled(mut self, disabled: bool) -> Self {
        self.disabled = disabled;
        self
    }

    pub fn props(mut self, props: ComponentProps) -> Self {
        self.props = props;
        self
    }

    fn get_variant_class(&self) -> &'static str {
        match self.variant {
            ButtonVariant::Primary => "bg-primary-600 text-white hover:bg-primary-700 focus:ring-primary-500",
            ButtonVariant::Secondary => "bg-secondary-600 text-white hover:bg-secondary-700 focus:ring-secondary-500",
            ButtonVariant::Outline => "bg-transparent border border-gray-300 text-gray-700 hover:bg-gray-50 focus:ring-primary-500",
            ButtonVariant::Ghost => "bg-transparent text-gray-600 hover:bg-gray-50 hover:text-gray-900 focus:ring-gray-500",
            ButtonVariant::Danger => "bg-red-600 text-white hover:bg-red-700 focus:ring-red-500",
        }
    }

    fn get_size_class(&self) -> &'static str {
        match self.size {
            ButtonSize::Sm => "px-3 py-1.5 text-xs",
            ButtonSize::Md => "px-4 py-2 text-sm",
            ButtonSize::Lg => "px-6 py-3 text-base",
            ButtonSize::Xl => "px-8 py-4 text-lg",
        }
    }
}

#[async_trait::async_trait]
impl UIComponent for Button {
    async fn render(&self) -> Result<String> {
        let base_class = "inline-flex items-center justify-center font-medium rounded-md shadow-sm transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed";
        let variant_class = self.get_variant_class();
        let size_class = self.get_size_class();
        let disabled_attr = if self.disabled { " disabled" } else { "" };

        let mut classes = vec![base_class, variant_class, size_class];
        if let Some(custom_class) = &self.props.class {
            classes.push(custom_class);
        }

        let class_attr = format!("class=\"{}\"", classes.join(" "));
        let attrs = format!("{}{}{}", class_attr, self.props.to_attributes(), disabled_attr);

        Ok(format!("<button{}>{}</button>", attrs, self.label))
    }

    fn name(&self) -> &str {
        "Button"
    }

    fn props(&self) -> &ComponentProps {
        &self.props
    }
}

/// Input component
#[derive(Debug, Clone)]
pub struct Input {
    pub input_type: InputType,
    pub placeholder: Option<String>,
    pub value: Option<String>,
    pub required: bool,
    pub props: ComponentProps,
}

#[derive(Debug, Clone)]
pub enum InputType {
    Text,
    Email,
    Password,
    Number,
    Tel,
    Url,
    Search,
}

impl Input {
    pub fn new(input_type: InputType) -> Self {
        Self {
            input_type,
            placeholder: None,
            value: None,
            required: false,
            props: ComponentProps::new(),
        }
    }

    pub fn text() -> Self {
        Self::new(InputType::Text)
    }

    pub fn email() -> Self {
        Self::new(InputType::Email)
    }

    pub fn password() -> Self {
        Self::new(InputType::Password)
    }

    pub fn placeholder(mut self, placeholder: impl Into<String>) -> Self {
        self.placeholder = Some(placeholder.into());
        self
    }

    pub fn value(mut self, value: impl Into<String>) -> Self {
        self.value = Some(value.into());
        self
    }

    pub fn required(mut self, required: bool) -> Self {
        self.required = required;
        self
    }

    pub fn props(mut self, props: ComponentProps) -> Self {
        self.props = props;
        self
    }

    fn get_type_attr(&self) -> &'static str {
        match self.input_type {
            InputType::Text => "text",
            InputType::Email => "email",
            InputType::Password => "password",
            InputType::Number => "number",
            InputType::Tel => "tel",
            InputType::Url => "url",
            InputType::Search => "search",
        }
    }
}

#[async_trait::async_trait]
impl UIComponent for Input {
    async fn render(&self) -> Result<String> {
        let base_class = "block w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm placeholder-gray-400 focus:outline-none focus:ring-primary-500 focus:border-primary-500 sm:text-sm";
        let type_attr = format!("type=\"{}\"", self.get_type_attr());

        let mut attrs = vec![type_attr];

        if let Some(placeholder) = &self.placeholder {
            attrs.push(format!("placeholder=\"{}\"", placeholder));
        }

        if let Some(value) = &self.value {
            attrs.push(format!("value=\"{}\"", value));
        }

        if self.required {
            attrs.push("required".to_string());
        }

        let mut classes = vec![base_class];
        if let Some(custom_class) = &self.props.class {
            classes.push(custom_class);
        }

        attrs.push(format!("class=\"{}\"", classes.join(" ")));
        attrs.push(self.props.to_attributes());

        Ok(format!("<input {}/>", attrs.join(" ")))
    }

    fn name(&self) -> &str {
        "Input"
    }

    fn props(&self) -> &ComponentProps {
        &self.props
    }
}

/// Card component
#[derive(Debug, Clone)]
pub struct Card {
    pub header: Option<String>,
    pub content: String,
    pub footer: Option<String>,
    pub variant: CardVariant,
    pub props: ComponentProps,
}

#[derive(Debug, Clone)]
pub enum CardVariant {
    Default,
    Elevated,
    Outlined,
    Ghost,
}

impl Card {
    pub fn new(content: impl Into<String>) -> Self {
        Self {
            header: None,
            content: content.into(),
            footer: None,
            variant: CardVariant::Default,
            props: ComponentProps::new(),
        }
    }

    pub fn header(mut self, header: impl Into<String>) -> Self {
        self.header = Some(header.into());
        self
    }

    pub fn footer(mut self, footer: impl Into<String>) -> Self {
        self.footer = Some(footer.into());
        self
    }

    pub fn variant(mut self, variant: CardVariant) -> Self {
        self.variant = variant;
        self
    }

    pub fn props(mut self, props: ComponentProps) -> Self {
        self.props = props;
        self
    }

    fn get_variant_class(&self) -> &'static str {
        match self.variant {
            CardVariant::Default => "bg-white rounded-lg shadow-sm border border-gray-200 p-6",
            CardVariant::Elevated => "bg-white rounded-lg shadow-lg border border-gray-200 p-6",
            CardVariant::Outlined => "bg-white rounded-lg border-2 border-gray-200 p-6",
            CardVariant::Ghost => "bg-white rounded-lg p-6",
        }
    }
}

#[async_trait::async_trait]
impl UIComponent for Card {
    async fn render(&self) -> Result<String> {
        let base_class = self.get_variant_class();

        let mut classes = vec![base_class];
        if let Some(custom_class) = &self.props.class {
            classes.push(custom_class);
        }

        let class_attr = format!("class=\"{}\"", classes.join(" "));
        let attrs = format!("{}{}", class_attr, self.props.to_attributes());

        let mut html = format!("<div{}>", attrs);

        if let Some(header) = &self.header {
            html.push_str(&format!("<div class=\"border-b border-gray-200 pb-4 mb-4\"><h3 class=\"text-lg font-medium text-gray-900\">{}</h3></div>", header));
        }

        html.push_str(&format!("<div class=\"text-gray-700\">{}</div>", self.content));

        if let Some(footer) = &self.footer {
            html.push_str(&format!("<div class=\"border-t border-gray-200 pt-4 mt-4\"><div class=\"text-sm text-gray-500\">{}</div></div>", footer));
        }

        html.push_str("</div>");
        Ok(html)
    }

    fn name(&self) -> &str {
        "Card"
    }

    fn props(&self) -> &ComponentProps {
        &self.props
    }
}

/// Container component
#[derive(Debug, Clone)]
pub struct Container {
    pub content: String,
    pub size: ContainerSize,
    pub props: ComponentProps,
}

#[derive(Debug, Clone)]
pub enum ContainerSize {
    Sm,
    Md,
    Lg,
    Xl,
    Full,
}

impl Container {
    pub fn new(content: impl Into<String>) -> Self {
        Self {
            content: content.into(),
            size: ContainerSize::Lg,
            props: ComponentProps::new(),
        }
    }

    pub fn size(mut self, size: ContainerSize) -> Self {
        self.size = size;
        self
    }

    pub fn props(mut self, props: ComponentProps) -> Self {
        self.props = props;
        self
    }

    fn get_size_class(&self) -> &'static str {
        match self.size {
            ContainerSize::Sm => "mx-auto max-w-3xl px-4 sm:px-6 lg:px-8",
            ContainerSize::Md => "mx-auto max-w-5xl px-4 sm:px-6 lg:px-8",
            ContainerSize::Lg => "mx-auto max-w-7xl px-4 sm:px-6 lg:px-8",
            ContainerSize::Xl => "mx-auto max-w-screen-xl px-4 sm:px-6 lg:px-8",
            ContainerSize::Full => "mx-auto max-w-full px-4 sm:px-6 lg:px-8",
        }
    }
}

#[async_trait::async_trait]
impl UIComponent for Container {
    async fn render(&self) -> Result<String> {
        let base_class = self.get_size_class();

        let mut classes = vec![base_class];
        if let Some(custom_class) = &self.props.class {
            classes.push(custom_class);
        }

        let class_attr = format!("class=\"{}\"", classes.join(" "));
        let attrs = format!("{}{}", class_attr, self.props.to_attributes());

        Ok(format!("<div{}>{}</div>", attrs, self.content))
    }

    fn name(&self) -> &str {
        "Container"
    }

    fn props(&self) -> &ComponentProps {
        &self.props
    }
}

/// Typography components
pub struct Heading {
    pub level: HeadingLevel,
    pub text: String,
    pub props: ComponentProps,
}

#[derive(Debug, Clone)]
pub enum HeadingLevel {
    H1,
    H2,
    H3,
    H4,
    H5,
    H6,
}

impl Heading {
    pub fn new(level: HeadingLevel, text: impl Into<String>) -> Self {
        Self {
            level,
            text: text.into(),
            props: ComponentProps::new(),
        }
    }

    pub fn h1(text: impl Into<String>) -> Self {
        Self::new(HeadingLevel::H1, text)
    }

    pub fn h2(text: impl Into<String>) -> Self {
        Self::new(HeadingLevel::H2, text)
    }

    pub fn h3(text: impl Into<String>) -> Self {
        Self::new(HeadingLevel::H3, text)
    }

    pub fn props(mut self, props: ComponentProps) -> Self {
        self.props = props;
        self
    }

    fn get_tag(&self) -> &'static str {
        match self.level {
            HeadingLevel::H1 => "h1",
            HeadingLevel::H2 => "h2",
            HeadingLevel::H3 => "h3",
            HeadingLevel::H4 => "h4",
            HeadingLevel::H5 => "h5",
            HeadingLevel::H6 => "h6",
        }
    }

    fn get_class(&self) -> &'static str {
        match self.level {
            HeadingLevel::H1 => "text-4xl font-bold text-gray-900 mb-4",
            HeadingLevel::H2 => "text-3xl font-bold text-gray-900 mb-3",
            HeadingLevel::H3 => "text-2xl font-semibold text-gray-900 mb-2",
            HeadingLevel::H4 => "text-xl font-semibold text-gray-900 mb-2",
            HeadingLevel::H5 => "text-lg font-medium text-gray-900 mb-1",
            HeadingLevel::H6 => "text-base font-medium text-gray-900 mb-1",
        }
    }
}

#[async_trait::async_trait]
impl UIComponent for Heading {
    async fn render(&self) -> Result<String> {
        let tag = self.get_tag();
        let base_class = self.get_class();

        let mut classes = vec![base_class];
        if let Some(custom_class) = &self.props.class {
            classes.push(custom_class);
        }

        let class_attr = format!("class=\"{}\"", classes.join(" "));
        let attrs = format!("{}{}", class_attr, self.props.to_attributes());

        Ok(format!("<{tag}{attrs}>{}</{tag}>", self.text))
    }

    fn name(&self) -> &str {
        "Heading"
    }

    fn props(&self) -> &ComponentProps {
        &self.props
    }
}

/// Component registry for managing UI components
pub struct ComponentRegistry {
    components: HashMap<String, Box<dyn UIComponent>>,
}

impl ComponentRegistry {
    pub fn new() -> Self {
        Self {
            components: HashMap::new(),
        }
    }

    pub fn register<C: UIComponent + 'static>(&mut self, name: impl Into<String>, component: C) {
        self.components.insert(name.into(), Box::new(component));
    }

    pub fn get(&self, name: &str) -> Option<&Box<dyn UIComponent>> {
        self.components.get(name)
    }

    pub async fn render_all(&self) -> Result<String> {
        let mut html = String::new();
        for component in self.components.values() {
            html.push_str(&component.render().await?);
            html.push('\n');
        }
        Ok(html)
    }
}

impl Default for ComponentRegistry {
    fn default() -> Self {
        Self::new()
    }
}

/// Utility functions for building UIs
pub mod utils {
    use super::*;

    /// Create a simple button with primary styling
    pub fn primary_button(label: impl Into<String>) -> Button {
        Button::new(label).variant(ButtonVariant::Primary)
    }

    /// Create a secondary button
    pub fn secondary_button(label: impl Into<String>) -> Button {
        Button::new(label).variant(ButtonVariant::Secondary)
    }

    /// Create an outline button
    pub fn outline_button(label: impl Into<String>) -> Button {
        Button::new(label).variant(ButtonVariant::Outline)
    }

    /// Create a text input
    pub fn text_input(placeholder: impl Into<String>) -> Input {
        Input::text().placeholder(placeholder)
    }

    /// Create an email input
    pub fn email_input(placeholder: impl Into<String>) -> Input {
        Input::email().placeholder(placeholder)
    }

    /// Create a password input
    pub fn password_input(placeholder: impl Into<String>) -> Input {
        Input::password().placeholder(placeholder)
    }

    /// Create a card with content
    pub fn card(content: impl Into<String>) -> Card {
        Card::new(content)
    }

    /// Create a container with content
    pub fn container(content: impl Into<String>) -> Container {
        Container::new(content)
    }

    /// Create headings
    pub fn h1(text: impl Into<String>) -> Heading {
        Heading::h1(text)
    }

    pub fn h2(text: impl Into<String>) -> Heading {
        Heading::h2(text)
    }

    pub fn h3(text: impl Into<String>) -> Heading {
        Heading::h3(text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_button_render() {
        let button = Button::new("Click me").variant(ButtonVariant::Primary);
        let html = button.render().await.unwrap();
        assert!(html.contains("Click me"));
        assert!(html.contains("bg-primary-600"));
    }

    #[tokio::test]
    async fn test_input_render() {
        let input = Input::text().placeholder("Enter text");
        let html = input.render().await.unwrap();
        assert!(html.contains("type=\"text\""));
        assert!(html.contains("placeholder=\"Enter text\""));
    }

    #[tokio::test]
    async fn test_card_render() {
        let card = Card::new("Card content").header("Card Title");
        let html = card.render().await.unwrap();
        assert!(html.contains("Card content"));
        assert!(html.contains("Card Title"));
    }

    #[tokio::test]
    async fn test_heading_render() {
        let heading = Heading::h1("Main Title");
        let html = heading.render().await.unwrap();
        assert!(html.contains("<h1"));
        assert!(html.contains("Main Title"));
        assert!(html.contains("</h1>"));
    }

    #[tokio::test]
    async fn test_component_registry() {
        let mut registry = ComponentRegistry::new();
        registry.register("button1", Button::new("Test Button"));
        registry.register("heading1", Heading::h1("Test Heading"));

        let html = registry.render_all().await.unwrap();
        assert!(html.contains("Test Button"));
        assert!(html.contains("Test Heading"));
    }
}
