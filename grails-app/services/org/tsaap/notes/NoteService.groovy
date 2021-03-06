/*
 * Copyright 2013 Tsaap Development Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tsaap.notes

import grails.orm.PagedResultList
import org.gcontracts.annotations.Requires
import org.hibernate.criterion.CriteriaSpecification
import org.springframework.transaction.annotation.Transactional
import org.tsaap.directory.User
import org.tsaap.questions.LiveSession
import org.tsaap.questions.LiveSessionService

class NoteService {

    static transactional = false
    LiveSessionService liveSessionService

    /**
     * Add a new note
     * @param author the author
     * @param content the content
     * @param context the context
     * @param parentNote the parent note
     * @return the added note
     */
    @Transactional
    @Requires({ author && content })
    Note addNote(User author,
                 String content,
                 Context context = null,
                 Tag fragmentTag = null,
                 Note parentNote = null) {

        // create the note
        Note theNote = new Note(author: author,
                content: content,
                context: context,
                fragmentTag: fragmentTag,
                parentNote: parentNote)
        // save the note
        theNote.save()

        // manage tags & mentions

        def tags = NoteHelper.tagsFromContent(content)
        def mentions = NoteHelper.mentionsFromContent(content)

        def contentFromContext = context?.descriptionAsNote
        if (contentFromContext) {
            tags += NoteHelper.tagsFromContent(contentFromContext)
            mentions += NoteHelper.mentionsFromContent(contentFromContext)
        }

        tags.each {
            Tag tag = Tag.findOrSaveWhere(name: it)
            new NoteTag(note: theNote, tag: tag).save()
        }

        mentions.each {
            User user = User.findByUsername(it)
            if (user) {
                new NoteMention(note: theNote, mention: user).save()
            }
        }

        // return the note
        theNote
    }

    /**
     * Bookmark a note by a user
     * @param theNote the note to bookmarked
     * @param theUser the bookmarker
     * @return the bookmark
     */
    @Requires({ theNote && theUser })
    Bookmark bookmarkNotebyUser(Note theNote, User theUser) {
        Bookmark bookmark = new Bookmark(user: theUser, note: theNote)
        // for performance issues the bookmarks bag coming from the to many relationship
        // is not set
        bookmark.save(failOnError: true)
    }

    /**
     * Unbookmark a note by a given user
     * @param note the note
     * @param user the user
     */
    @Requires({ note && user })
    def unbookmarkedNoteByUser(Note note, User user) {
        Bookmark bookmark = Bookmark.findByNoteAndUser(note, user)
        if (bookmark) {
            note.removeFromBookmarks(bookmark)
            bookmark.delete(flush: true)
        }
    }

    /**
     * Delete a note
     * @param note the note to delete
     * @param user the author of the note
     */
    @Transactional
    @Requires({ note && note.author == user })
    def deleteNoteByAuthor(Note note, User user) {
        // set parentNote fields
        def query = Note.where {
            parentNote == note
        }
        query.updateAll(parentNote: null)
        // delete tags if any
        query = NoteTag.where {
            note == note
        }
        query.deleteAll()
        // delete mentions if any
        query = NoteMention.where {
            note == note
        }
        query.deleteAll()
        // delete bookmarks if any
        query = Bookmark.where {
            note == note
        }
        query.deleteAll()

        // delete live sessions if any
        query = LiveSession.where {
            note == note
        }
        query.findAll { liveSessionService.deleteLiveSessionByAuthor(it,user) }
        query.deleteAll()
        // finally delete notes
        note.delete()
    }

    /**
     * Find all notes for the given search criteria
     * @param inUser the user performing the search
     * @param userNotes indicates if the search must find the notes the user is owner
     * @param userFavorites indicates if the search must find the user favorite notes
     * @param all indicates if the search must find all notes for a given context
     * @param inContext the given context
     * @param paginationAndSorting specification of pagination and sorting
     * @return the notes corresponding to the search
     */
    def findAllNotes(User inUser,
                     Boolean userNotes = true,
                     Boolean userFavorites = false,
                     Boolean all = false,
                     Context inContext = null,
                     Tag inFragmentTag = null,
                     Map paginationAndSorting = [sort: 'dateCreated', order: 'desc']) {
        if (!(userNotes || userFavorites || all)) {
            return new DefaultPagedResultList(list: [], totalCount: 0)
        }
        if (!inContext) { // all is not relevant when there is no context
            all = false
        }
        if (all && !inFragmentTag) { // we have a context and user want all notes on the context
            return new DefaultPagedResultList(list: Note.findAllByContext(inContext, paginationAndSorting),
                    totalCount: Note.countByContext(inContext, paginationAndSorting))
        }
        if (all && inFragmentTag) {
            return new DefaultPagedResultList(list: Note.findAllByContextAndFragmentTag(inContext, inFragmentTag, paginationAndSorting),
                    totalCount: Note.countByContextAndFragmentTag(inContext, inFragmentTag, paginationAndSorting))
        }
        // if not all, we use a criteria
        def criteria = Note.createCriteria()
        PagedResultList results = criteria.list(paginationAndSorting) {
            createAlias('bookmarks', 'bmks', CriteriaSpecification.LEFT_JOIN)
            if (inContext) { // if there is a context
                eq 'context', inContext
                if (inFragmentTag) { // if there is a context and a fragmentTag
                    eq 'fragmentTag', inFragmentTag
                }
            }
            // we know that one of the two filters is active
            or {
                if (userNotes) {
                    eq 'author', inUser
                }
                if (userFavorites) {
                    eq 'bmks.user', inUser
                }
            }
            order paginationAndSorting.sort, paginationAndSorting.order
        }
        new DefaultPagedResultList(list: results.list, totalCount: results.totalCount)
    }
}

/**
 * Custom paged result list*/
class DefaultPagedResultList {

    List list
    Long totalCount

}


