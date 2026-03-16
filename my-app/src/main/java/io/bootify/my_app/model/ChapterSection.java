package io.bootify.my_app.model;

public class ChapterSection {

    private String title;
    private int chapterIndex;
    private String text;

    public ChapterSection() {
    }

    public ChapterSection(String title, int chapterIndex, String text) {
        this.title = title;
        this.chapterIndex = chapterIndex;
        this.text = text;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getChapterIndex() {
        return chapterIndex;
    }

    public void setChapterIndex(int chapterIndex) {
        this.chapterIndex = chapterIndex;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
