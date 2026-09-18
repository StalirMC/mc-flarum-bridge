<?php

namespace Stalir\McBridge\Service;

use Flarum\Locale\Translator;
use Flarum\Settings\SettingsRepositoryInterface;
use Symfony\Component\Translation\MessageCatalogueInterface;

/**
 * Resolves the language used for everything this extension outputs.
 *
 * The locale is taken from the `mc-bridge.locale` setting and always falls back
 * to Simplified Chinese, regardless of the forum's own default locale, so the
 * extension's console output and API errors are Chinese out of the box.
 *
 * The locale is passed explicitly to the translator instead of being pushed
 * into it with setLocale(), because the translator is a shared singleton and
 * mutating it would leak into the rest of the request.
 */
final class BridgeMessages
{
    /** Top level key used by locale/*.yml, and the translation key prefix. */
    public const PACKAGE = 'stalirmc-mc-bridge';

    /** Language used when nothing else is configured. */
    public const DEFAULT_LOCALE = 'zh-Hans';

    public const SETTING_KEY = 'mc-bridge.locale';

    /**
     * Domain Flarum registers every locale file under
     * (LocaleManager::addTranslations), which is what makes the messages render
     * through ICU MessageFormat.
     */
    public const DOMAIN = 'messages'.MessageCatalogueInterface::INTL_DOMAIN_SUFFIX;

    private string $locale;

    public function __construct(
        private readonly Translator $translator,
        private readonly SettingsRepositoryInterface $settings
    ) {
        $this->locale = self::resolveLocale($settings);
    }

    public function locale(): string
    {
        return $this->locale;
    }

    /**
     * Translate one of this extension's keys.
     *
     * Flarum registers locale files in the "messages+intl-icu" domain, so
     * messages are rendered with ICU MessageFormat: placeholders are written
     * {like_this}, NOT Symfony's %like_this%. A %placeholder% is silently left
     * untouched and shows up verbatim in the output.
     *
     * @param  array<string, string>  $replace  placeholder name => value, without braces.
     */
    public function get(string $suffix, array $replace = []): string
    {
        $key = self::PACKAGE.'.'.$suffix;

        try {
            return $this->translator->get($key, $replace, $this->locale);
        } catch (\Throwable $exception) {
            // ICU throws on a malformed pattern or a missing argument, and a
            // translation slip must not take a command down.
            return $this->fromCatalogue($key, $replace);
        }
    }

    /**
     * Last-resort rendering straight from the message catalogue, bypassing the
     * ICU formatter and substituting {name} literally.
     *
     * @param  array<string, string>  $replace
     */
    private function fromCatalogue(string $key, array $replace): string
    {
        try {
            $template = $this->translator->getCatalogue($this->locale)->get($key, self::DOMAIN);
        } catch (\Throwable $exception) {
            return $key;
        }

        foreach ($replace as $name => $value) {
            $template = str_replace('{'.$name.'}', (string) $value, $template);
        }

        return $template;
    }

    public static function resolveLocale(SettingsRepositoryInterface $settings): string
    {
        $configured = trim((string) $settings->get(self::SETTING_KEY, ''));

        if ($configured !== '' && self::isAvailable($configured)) {
            return $configured;
        }

        if (self::isAvailable(self::DEFAULT_LOCALE)) {
            return self::DEFAULT_LOCALE;
        }

        return self::availableLocales()[0] ?? 'en';
    }

    /**
     * Languages shipped in locale/, i.e. the values accepted by the setting.
     *
     * @return string[]
     */
    public static function availableLocales(): array
    {
        $files = glob(dirname(__DIR__, 2).'/locale/*.yml') ?: [];

        $locales = array_map(
            static fn (string $file): string => basename($file, '.yml'),
            $files
        );

        sort($locales);

        return array_values($locales);
    }

    public static function isAvailable(string $locale): bool
    {
        return $locale !== '' && in_array($locale, self::availableLocales(), true);
    }
}
