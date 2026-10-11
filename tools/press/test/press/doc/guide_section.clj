(ns press.doc.guide-section
  "An invented library handbook section, written as `press.doc` data.
   Its tables, prose and glossary exercise document composition without
   describing any real organization, device or deployment.")

(defn- code
  "A code span."
  [s]
  [:doc/code s])

(def section-1
  "The library's fictional welcome-desk section, as data."
  {:id "welcome"
   :title "At the welcome desk"
   :blocks
   [[:doc/lead "The pop-up library occupies a borrowed shop for an imaginary reading festival. A volunteer at the welcome desk helps visitors find books, joins new readers and accepts donations. A paper notebook records questions for the next shift."]
    [:doc/p [:b "Before opening."] " Put the " [:doc/term "reading card"]
     " forms beside the pencils. Display today's " [:em "reading circle"]
     " on the noticeboard and place the return basket within reach of the door."]
    [:doc/p "The table gives a starting point for each kind of visit:"]
    [:doc/table
     {:caption "Helping visitors."
      :cols [25 45 30]
      :head ["visitor asks" "volunteer does" "where to go next"]
      :rows [["May I join?" "Offer a reading card and explain how to borrow a book."
              ["Blank forms are in " (code "forms/readers/new.edn") "."]]
             ["Where are the maps?" "Point out the reference shelves and offer a reading seat."
              "The window table has room to unfold an atlas."]
             ["Can I return this?" "Place the book in the return basket with its paper slip."
              "The shelving volunteer checks the basket after the reading circle."]
             ["When is story time?" "Read today's noticeboard together."
              "The notice names the story and the volunteer leading it."]]}]
    [:doc/p [:b "Donations."] " Thank the visitor before inspecting a donation. A "
     [:doc/term "condition slip"] " records whether a book is ready for the shelves or needs attention."]
    [:doc/p "Use these example decisions when sorting a donation:"]
    [:doc/table
     {:caption "Sorting donated books."
      :cols [36 64]
      :head ["condition" "next action"]
      :rows [["Clean, complete and useful" "Place it on the cataloguing table with a fresh condition slip."]
             ["A loose cover or a torn page" "Set it aside for the book-care volunteer to inspect."]
             ["Outside this festival's collection" "Explain the collection's theme and offer to return the book."]]}]
    [:doc/p "At handover, read the notebook with the next volunteer. Leave unresolved requests visible rather than guessing an answer."]]})

(def document
  "A complete fictional handbook carrying the welcome-desk section."
  {:title "An imaginary pop-up library"
   :kicker "Example handbook · reading festival"
   :what-this-is "A fictional volunteer handbook used to demonstrate the document vocabulary. Its people, activities and forms are invented examples."
   :audience "volunteers in the example reading festival"
   :snapshot {:commit "0000000" :date "2000-01-01"}
   :problem [[:doc/lead "Visitors need an easy place to ask a question before choosing a book."]]
   :status [[:doc/lead "This handbook is a composition fixture, not a report about a running library."]]
   :body [{:title "A day at the festival"
           :summary "Prepare the desk, welcome visitors and hand over the notebook."
           :sections [section-1]}]
   :open-questions [[:doc/lead "The example leaves the festival's opening hours and collection theme for its imagined organizers to choose."]]
   :glossary [{:term "condition slip"
               :def "a paper note describing whether a donated book is ready for a shelf or needs attention."}
              {:term "reading card"
               :def "the example library's paper record of a reader's borrowed books."}]
   :sources [[:doc/lead "The fixture author invented this handbook. It contains no measurements or operational records."]
             [:doc/table {:cols [35 65] :head ["example material" "purpose"]
                          :rows [["visitor questions" "exercise paragraph and table composition"]
                                 ["form path and revision marker" "exercise code spans and snapshot rendering; neither identifies a real file or commit"]]}]]})
