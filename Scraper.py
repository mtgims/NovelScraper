import requests
from bs4 import BeautifulSoup
import time
import os
import re
from urllib.parse import urljoin, urlparse
from ebooklib import epub
import html
import sys

class NovelScraper:
    def __init__(self, base_url, delay=1):
        self.base_url = base_url
        self.delay = delay
        self.session = requests.Session()
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
        })

    # Get all chapter links from the book's chapter list pages
    def get_chapter_list(self, book_name):
        chapters = []
        page = 1

        print("Fetching chapter list for '{}'...".format(book_name))

        while True:
            url = "{}/book/{}/chapters?page={}".format(self.base_url, book_name, page)
            print("Fetching page {}: {}".format(page, url))

            try:
                response = self.session.get(url)
                response.raise_for_status()

                soup = BeautifulSoup(response.content, 'html.parser')
                chapter_list = soup.find('ul', class_='chapter-list')

                if not chapter_list:
                    print("No chapter list found on page {}".format(page))
                    break

                chapter_links = chapter_list.find_all('a')

                if not chapter_links:
                    print("No chapters found on page {}".format(page))
                    break

                for link in chapter_links:
                    href = link.get('href')
                    title = link.get('title', '')
                    chapter_no_span = link.find('span', class_='chapter-no')
                    chapter_no = chapter_no_span.text.strip() if chapter_no_span else ''

                    if href:
                        full_url = urljoin(self.base_url, href)
                        chapters.append({
                            'number': chapter_no,
                            'title': title,
                            'url': full_url
                        })

                print("Found {} chapters on page {}".format(len(chapter_links), page))
                page += 1
                time.sleep(self.delay)
            except requests.RequestException as e:
                print(f"Error fetching page {page}: {e}")
                break
        
        print(f"Total chapters found: {len(chapters)}")
        return chapters
    
    # Fetch content of a single chapter
    def get_chapter_content(self, chapter_url):
        try:
            response = self.session.get(chapter_url)
            response.raise_for_status()

            soup = BeautifulSoup(response.content, 'html.parser')
            content_div = soup.find('div', id='content')

            if content_div:
                for script in content_div(["script", "style"]):
                    script.decompose()

                content = str(content_div)
                return content
            else:
                print(f"No content div found for {chapter_url}")
                return None
        except requests.RequestException as e:
            print(f"Error fetching content from {chapter_url}: {e}")
            return None
        
    # Creating an EPUB file
    def create_epub(self, chapters, book_name, volume_number):
        book = epub.EpubBook()

        # Book metadata
        book_title = "{} - Volume {}".format(book_name.replace('-', ' ').title(), volume_number)
        book.set_identifier("{}-vol{}".format(book_name, volume_number))
        book.set_title(book_title)
        book.set_language('en')
        book.add_author('Unknown Author')

        epub_chapters = []

        for i, chapter in enumerate(chapters):
            print("Processing chapter {}: {}".format(chapter['number'], chapter['title']))

            content = self.get_chapter_content(chapter['url'])
            if not content:
                print(f"Skipping chapter {chapter['number']} for missing content")
                continue

            chapter_title = "Chapter {}: {}".format(chapter['number'], chapter['title'])
            chapter_filename = "chapter_{}.xhtml".format(chapter['number'])

            epub_chapter = epub.EpubHtml(
                title=chapter_title,
                file_name=chapter_filename,
                lang='en'
            )

            chapter_html = """
            <html>
            <head>
                <title>{}</title>
            </head>
            <body>
                <h1>{}</h1>
                {}
            </body>
            </html>
            """.format(html.escape(chapter_title), html.escape(chapter_title), content)

            # Fix: Set content for the chapter object, not the list
            epub_chapter.set_content(chapter_html)
            book.add_item(epub_chapter)
            epub_chapters.append(epub_chapter)

            time.sleep(self.delay)

        book.toc = (epub.Link("nav.xhtml", "Table of Contents", "nav"),
                   (epub.Section("Chapters"), epub_chapters))
        
        book.add_item(epub.EpubNcx())
        book.add_item(epub.EpubNav())

        book.spine = ['nav'] + epub_chapters
        output_dir = "epub_output"
        os.makedirs(output_dir, exist_ok=True)

        filename = f"{book_name}-volume-{volume_number}.epub"
        filepath = os.path.join(output_dir, filename)

        epub.write_epub(filepath, book, {})
        print(f"EPUB created: {filepath}")
        return filepath
    
    # Function to scrapte chapters
    def scapre_and_convert(self, book_name, chapers_per_volume=100):
        all_chapters = self.get_chapter_list(book_name)

        if not all_chapters:
            print("No chapters found!")
            return
        
        volume_number = 1
        created_files = []

        for i in range(0, len(all_chapters), chapers_per_volume):
            volume_chapters = all_chapters[i:i + chapers_per_volume]
            print(f"\nCreating Volume {volume_number} with {len(volume_chapters)} chapters...")

            epub_file = self.create_epub(volume_chapters, book_name, volume_number)

            if epub_file:
                created_files.append(epub_file)

            volume_number += 1

        print(f"\nCompleted! Created {len(created_files)} EPUB files:")
        for file in created_files:
            print(f" - {file}")

def main():
    book_name = input("Enter the book name (as it appears in the URL): ").strip()

    if not book_name:
        print("Book name cannot be empty!")
        return
    
    try:
        chapters_input = input("Enters chapters per EPUB file (default: 100): ").strip()
        chapters_per_volume = int(chapters_input) if chapters_input else 100
    except ValueError:
        chapters_per_volume = 100
        print("Invalide input, using default of 100 chapters")

    try:
        delay_input = input("Enter delay between requests in seconds (default: 1): ").strip()
        delay = float(delay_input) if delay_input else 1.0
    except ValueError:
        delay = 1.0
        print("Input is invalid, using default 1 second")

    scraper = NovelScraper(base_url="https://novelfire.net", delay=delay)

    try:
        scraper.scapre_and_convert(book_name, chapters_per_volume)
    except KeyboardInterrupt:
        print("\nProcess interrupted")
    except Exception as e:
        print(f"An error occurred: {e}")

if __name__ == "__main__":
    main()
